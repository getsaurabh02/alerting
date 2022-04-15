package org.opensearch.alerting.triggercondition.parsers

import org.opensearch.alerting.triggercondition.resolvers.TriggerExpressionResolver

interface ExpressionParser {
    fun parse(): TriggerExpressionResolver
}
