package io.legado.app.ui.filter.edit

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.widget.EditText
import androidx.activity.viewModels
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.data.entities.FilterRule
import io.legado.app.databinding.ActivityFilterEditBinding
import io.legado.app.lib.dialogs.SelectItem
import io.legado.app.ui.widget.keyboard.KeyboardToolPop
import io.legado.app.utils.GSON
import io.legado.app.utils.imeHeight
import io.legado.app.utils.sendToClip
import io.legado.app.utils.shareWithQr
import io.legado.app.utils.showHelp
import io.legado.app.utils.viewbindingdelegate.viewBinding

/**
 * 编辑过滤器规则
 */
class FilterEditActivity :
    VMBaseActivity<ActivityFilterEditBinding, FilterEditViewModel>(),
    KeyboardToolPop.CallBack {

    companion object {

        fun startIntent(
            context: Context,
            id: Long = -1,
            pattern: String? = null,
            isRegex: Boolean = false
        ): Intent {
            val intent = Intent(context, FilterEditActivity::class.java)
            intent.putExtra("id", id)
            intent.putExtra("pattern", pattern)
            intent.putExtra("isRegex", isRegex)
            return intent
        }

    }

    override val binding by viewBinding(ActivityFilterEditBinding::inflate)
    override val viewModel by viewModels<FilterEditViewModel>()

    private val softKeyboardTool by lazy {
        KeyboardToolPop(this, lifecycleScope, binding.root, this)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        softKeyboardTool.attachToWindow(window)
        initView()
        viewModel.initData(intent) {
            upFilterView(it)
        }
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.rule_edit, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_save -> viewModel.save(getFilterRule()) {
                setResult(RESULT_OK)
                finish()
            }

            R.id.menu_copy_rule -> sendToClip(GSON.toJson(getFilterRule()))
            R.id.menu_paste_rule -> viewModel.pasteRule {
                upFilterView(it)
            }
            R.id.menu_share_qr -> shareWithQr(
                GSON.toJson(getFilterRule()),
                getString(R.string.share_filter_rule),
                ErrorCorrectionLevel.L
            )
        }
        return true
    }

    override fun onDestroy() {
        super.onDestroy()
        softKeyboardTool.dismiss()
    }

    private fun initView() {
        binding.ivHelp.setOnClickListener {
            showHelp("regexHelp")
        }
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, windowInsets ->
            softKeyboardTool.initialPadding = windowInsets.imeHeight
            windowInsets
        }
    }

    @SuppressLint("SetTextI18n")
    private fun upFilterView(filterRule: FilterRule) = binding.run {
        etName.setText(filterRule.name)
        etFilterRule.setText(filterRule.pattern)
        cbUseRegex.isChecked = filterRule.isRegex
        etTimeout.setText(filterRule.timeoutMillisecond.toString())
    }

    private fun getFilterRule(): FilterRule = binding.run {
        val filterRule: FilterRule = viewModel.filterRule ?: FilterRule()
        filterRule.name = etName.text.toString()
        filterRule.pattern = etFilterRule.text.toString()
        filterRule.isRegex = cbUseRegex.isChecked
        filterRule.timeoutMillisecond = etTimeout.text.toString().ifEmpty { "3000" }.toLong()
        return filterRule
    }

    override fun helpActions(): List<SelectItem<String>> {
        return arrayListOf(
            SelectItem("正则教程", "regexHelp")
        )
    }

    override fun onHelpActionSelect(action: String) {
        when (action) {
            "regexHelp" -> showHelp("regexHelp")
        }
    }

    override fun sendText(text: String) {
        if (text.isBlank()) return
        val view = window?.decorView?.findFocus()
        if (view is EditText) {
            val start = view.selectionStart
            val end = view.selectionEnd
            //获取EditText的文字
            val edit = view.editableText
            if (start < 0 || start >= edit.length) {
                edit.append(text)
            } else {
                //光标所在位置插入文字
                edit.replace(start, end, text)
            }
        }
    }

}
