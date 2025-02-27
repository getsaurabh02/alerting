/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.opensearch.alerting.transport

import org.apache.logging.log4j.LogManager
import org.opensearch.OpenSearchStatusException
import org.opensearch.action.ActionListener
import org.opensearch.action.admin.indices.create.CreateIndexResponse
import org.opensearch.action.get.GetRequest
import org.opensearch.action.get.GetResponse
import org.opensearch.action.index.IndexRequest
import org.opensearch.action.index.IndexResponse
import org.opensearch.action.support.ActionFilters
import org.opensearch.action.support.HandledTransportAction
import org.opensearch.action.support.master.AcknowledgedResponse
import org.opensearch.alerting.action.IndexEmailGroupAction
import org.opensearch.alerting.action.IndexEmailGroupRequest
import org.opensearch.alerting.action.IndexEmailGroupResponse
import org.opensearch.alerting.core.ScheduledJobIndices
import org.opensearch.alerting.core.model.ScheduledJob.Companion.SCHEDULED_JOBS_INDEX
import org.opensearch.alerting.core.model.ScheduledJob.Companion.SCHEDULED_JOB_TYPE
import org.opensearch.alerting.settings.AlertingSettings.Companion.INDEX_TIMEOUT
import org.opensearch.alerting.settings.DestinationSettings.Companion.ALLOW_LIST
import org.opensearch.alerting.util.AlertingException
import org.opensearch.alerting.util.DestinationType
import org.opensearch.alerting.util.IndexUtils
import org.opensearch.client.Client
import org.opensearch.cluster.service.ClusterService
import org.opensearch.common.inject.Inject
import org.opensearch.common.settings.Settings
import org.opensearch.common.xcontent.NamedXContentRegistry
import org.opensearch.common.xcontent.ToXContent
import org.opensearch.common.xcontent.XContentFactory.jsonBuilder
import org.opensearch.rest.RestRequest
import org.opensearch.rest.RestStatus
import org.opensearch.tasks.Task
import org.opensearch.transport.TransportService

private val log = LogManager.getLogger(TransportIndexEmailGroupAction::class.java)

class TransportIndexEmailGroupAction @Inject constructor(
    transportService: TransportService,
    val client: Client,
    actionFilters: ActionFilters,
    val scheduledJobIndices: ScheduledJobIndices,
    val clusterService: ClusterService,
    settings: Settings,
    val xContentRegistry: NamedXContentRegistry
) : HandledTransportAction<IndexEmailGroupRequest, IndexEmailGroupResponse>(
    IndexEmailGroupAction.NAME, transportService, actionFilters, ::IndexEmailGroupRequest
) {

    @Volatile private var indexTimeout = INDEX_TIMEOUT.get(settings)
    @Volatile private var allowList = ALLOW_LIST.get(settings)

    init {
        clusterService.clusterSettings.addSettingsUpdateConsumer(INDEX_TIMEOUT) { indexTimeout = it }
        clusterService.clusterSettings.addSettingsUpdateConsumer(ALLOW_LIST) { allowList = it }
    }

    override fun doExecute(task: Task, request: IndexEmailGroupRequest, actionListener: ActionListener<IndexEmailGroupResponse>) {
        client.threadPool().threadContext.stashContext().use {
            IndexEmailGroupHandler(client, actionListener, request).start()
        }
    }

    inner class IndexEmailGroupHandler(
        private val client: Client,
        private val actionListener: ActionListener<IndexEmailGroupResponse>,
        private val request: IndexEmailGroupRequest
    ) {

        fun start() {
            if (!scheduledJobIndices.scheduledJobIndexExists()) {
                scheduledJobIndices.initScheduledJobIndex(object : ActionListener<CreateIndexResponse> {
                    override fun onResponse(response: CreateIndexResponse) {
                        onCreateMappingsResponse(response)
                    }

                    override fun onFailure(e: Exception) {
                        actionListener.onFailure(e)
                    }
                })
            } else if (!IndexUtils.scheduledJobIndexUpdated) {
                IndexUtils.updateIndexMapping(
                    SCHEDULED_JOBS_INDEX, SCHEDULED_JOB_TYPE,
                    ScheduledJobIndices.scheduledJobMappings(), clusterService.state(), client.admin().indices(),
                    object : ActionListener<AcknowledgedResponse> {
                        override fun onResponse(response: AcknowledgedResponse) {
                            onUpdateMappingsResponse(response)
                        }

                        override fun onFailure(e: Exception) {
                            actionListener.onFailure(e)
                        }
                    }
                )
            } else {
                prepareEmailGroupIndexing()
            }
        }

        private fun prepareEmailGroupIndexing() {

            if (!allowList.contains(DestinationType.EMAIL.value)) {
                actionListener.onFailure(
                    AlertingException.wrap(
                        OpenSearchStatusException(
                            "This API is blocked since Destination type [${DestinationType.EMAIL}] is not allowed",
                            RestStatus.FORBIDDEN
                        )
                    )
                )
                return
            }

            if (request.method == RestRequest.Method.PUT) {
                updateEmailGroup()
            } else {
                indexEmailGroup()
            }
        }

        private fun onCreateMappingsResponse(response: CreateIndexResponse) {
            if (response.isAcknowledged) {
                log.info("Created $SCHEDULED_JOBS_INDEX with mappings.")
                prepareEmailGroupIndexing()
                IndexUtils.scheduledJobIndexUpdated()
            } else {
                log.error("Create $SCHEDULED_JOBS_INDEX mappings call not acknowledged.")
                actionListener.onFailure(
                    AlertingException.wrap(
                        OpenSearchStatusException(
                            "Create $SCHEDULED_JOBS_INDEX mappings call not acknowledged.",
                            RestStatus.INTERNAL_SERVER_ERROR
                        )
                    )
                )
            }
        }

        private fun onUpdateMappingsResponse(response: AcknowledgedResponse) {
            if (response.isAcknowledged) {
                log.info("Updated $SCHEDULED_JOBS_INDEX with mappings.")
                IndexUtils.scheduledJobIndexUpdated()
                prepareEmailGroupIndexing()
            } else {
                log.error("Update $SCHEDULED_JOBS_INDEX mappings call not acknowledged.")
                actionListener.onFailure(
                    AlertingException.wrap(
                        OpenSearchStatusException(
                            "Update $SCHEDULED_JOBS_INDEX mappings call not acknowledged.",
                            RestStatus.INTERNAL_SERVER_ERROR
                        )
                    )
                )
            }
        }

        private fun indexEmailGroup(update: Boolean = false) {
            request.emailGroup = request.emailGroup.copy(schemaVersion = IndexUtils.scheduledJobIndexSchemaVersion)
            var indexRequest = IndexRequest(SCHEDULED_JOBS_INDEX)
                .setRefreshPolicy(request.refreshPolicy)
                .source(request.emailGroup.toXContent(jsonBuilder(), ToXContent.MapParams(mapOf("with_type" to "true"))))
                .setIfSeqNo(request.seqNo)
                .setIfPrimaryTerm(request.primaryTerm)
                .timeout(indexTimeout)

            // If request is to update, then add id to index request
            if (update) indexRequest = indexRequest.id(request.emailGroupID)

            client.index(
                indexRequest,
                object : ActionListener<IndexResponse> {
                    override fun onResponse(response: IndexResponse) {
                        val failureReasons = checkShardsFailure(response)
                        if (failureReasons != null) {
                            actionListener.onFailure(
                                AlertingException.wrap(
                                    OpenSearchStatusException(failureReasons.toString(), response.status())
                                )
                            )
                            return
                        }
                        actionListener.onResponse(
                            IndexEmailGroupResponse(
                                response.id, response.version, response.seqNo, response.primaryTerm,
                                RestStatus.CREATED, request.emailGroup
                            )
                        )
                    }

                    override fun onFailure(e: Exception) {
                        actionListener.onFailure(e)
                    }
                }
            )
        }

        private fun updateEmailGroup() {
            val getRequest = GetRequest(SCHEDULED_JOBS_INDEX, request.emailGroupID)
            client.get(
                getRequest,
                object : ActionListener<GetResponse> {
                    override fun onResponse(response: GetResponse) {
                        onGetResponse(response)
                    }

                    override fun onFailure(e: Exception) {
                        actionListener.onFailure(e)
                    }
                }
            )
        }

        private fun onGetResponse(response: GetResponse) {
            if (!response.isExists) {
                actionListener.onFailure(
                    AlertingException.wrap(
                        OpenSearchStatusException("EmailGroup with ${request.emailGroupID} was not found", RestStatus.NOT_FOUND)
                    )
                )
                return
            }

            indexEmailGroup(update = true)
        }

        private fun checkShardsFailure(response: IndexResponse): String? {
            val failureReasons = StringBuilder()
            if (response.shardInfo.failed > 0) {
                response.shardInfo.failures.forEach {
                    entry ->
                    failureReasons.append(entry.reason())
                }

                return failureReasons.toString()
            }

            return null
        }
    }
}
