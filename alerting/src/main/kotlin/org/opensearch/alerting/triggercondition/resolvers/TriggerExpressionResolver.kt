package org.opensearch.alerting.triggercondition.resolvers

import org.opensearch.alerting.core.model.DocLevelQuery

interface TriggerExpressionResolver {
    fun evaluate(queryToDocIds: Map<DocLevelQuery, Set<String>>): Set<String>
}
