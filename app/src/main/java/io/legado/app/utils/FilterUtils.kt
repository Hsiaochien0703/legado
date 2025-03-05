package io.legado.app.utils

import io.legado.app.constant.AppLog
import io.legado.app.data.appDb

/**
 * 过滤器相关
 */
@Suppress("unused")
object FilterUtils {

    fun test(name: String): Boolean {
        val filterRules = appDb.filterRuleDao.enabled
        filterRules.forEach { item ->
            if (item.pattern.isNotEmpty()) {
                if (item.isRegex) {
                    if (name.contains(item.regex)) {
                        AppLog.put("filter by regex. \nregex: `${item.pattern}` \nname: $name \nresult: ${name.contains(item.regex)}")
                        return true
                    }
                } else {
                    if (name.contains(item.pattern, ignoreCase = true)) {
                        AppLog.put("filter by string. \nstring: `${item.pattern}` \nname: $name \nresult: ${name.contains(item.pattern, ignoreCase = true)}")
                        return true
                    }
                }
            }
        }
        return false
    }
}
