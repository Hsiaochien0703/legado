package io.legado.app.help

import io.legado.app.data.entities.FilterRule
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.*

object FilterAnalyzer {

    fun jsonToFilterRules(json: String): Result<MutableList<FilterRule>> {
        return kotlin.runCatching {
            val filterRules = mutableListOf<FilterRule>()
            val items: List<Map<String, Any>> = jsonPath.parse(json).read("$")
            for (item in items) {
                val jsonItem = jsonPath.parse(item)
                jsonToFilterRule(jsonItem.jsonString()).getOrThrow().let {
                    if (it.pattern.isValid(it.isRegex)) {
                        filterRules.add(it)
                    }
                }
            }
            filterRules
        }
    }

    fun jsonToFilterRule(json: String): Result<FilterRule> {
        return runCatching {
            val filterRule: FilterRule? =
                GSON.fromJsonObject<FilterRule>(json.trim()).getOrNull()
            if (filterRule == null || filterRule.pattern.isEmpty()) {
                val jsonItem = jsonPath.parse(json.trim())
                val rule = FilterRule()
                rule.id = jsonItem.readLong("$.id") ?: System.currentTimeMillis()
                rule.pattern = jsonItem.readString("$.regex") ?: ""
                if (rule.pattern.isEmpty()) throw NoStackTraceException("格式不对")
                rule.name = jsonItem.readString("$.name") ?: ""
                rule.isRegex = jsonItem.readBool("$.isRegex") == true
                rule.isEnabled = jsonItem.readBool("$.isEnabled") == true
                rule.order = jsonItem.readInt("$.order") ?: 0
                return@runCatching rule
            }
            return@runCatching filterRule
        }
    }

}