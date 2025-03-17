package io.legado.app.ui.filter

import android.annotation.SuppressLint
import android.os.Bundle
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SubMenu
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.widget.PopupMenu
import androidx.appcompat.widget.SearchView
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import io.legado.app.R
import io.legado.app.base.VMBaseActivity
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.FilterRule
import io.legado.app.databinding.ActivityFilterRuleBinding
import io.legado.app.databinding.DialogEditTextBinding
import io.legado.app.help.DirectLinkUpload
import io.legado.app.lib.dialogs.alert
import io.legado.app.lib.theme.primaryColor
import io.legado.app.lib.theme.primaryTextColor
import io.legado.app.ui.association.ImportFilterRuleDialog
import io.legado.app.ui.file.HandleFileContract
import io.legado.app.ui.filter.edit.FilterEditActivity
import io.legado.app.ui.qrcode.QrCodeResult
import io.legado.app.ui.widget.SelectActionBar
import io.legado.app.ui.widget.recycler.DragSelectTouchHelper
import io.legado.app.ui.widget.recycler.ItemTouchCallback
import io.legado.app.ui.widget.recycler.VerticalDivider
import io.legado.app.utils.ACache
import io.legado.app.utils.GSON
import io.legado.app.utils.applyTint
import io.legado.app.utils.hideSoftInput
import io.legado.app.utils.isAbsUrl
import io.legado.app.utils.launch
import io.legado.app.utils.readText
import io.legado.app.utils.sendToClip
import io.legado.app.utils.setEdgeEffectColor
import io.legado.app.utils.shouldHideSoftInput
import io.legado.app.utils.showDialogFragment
import io.legado.app.utils.showHelp
import io.legado.app.utils.splitNotBlank
import io.legado.app.utils.toastOnUi
import io.legado.app.utils.viewbindingdelegate.viewBinding
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch

/**
 * 过滤器规则管理
 */
class FilterRuleActivity : VMBaseActivity<ActivityFilterRuleBinding, FilterRuleViewModel>(),
    SearchView.OnQueryTextListener,
    PopupMenu.OnMenuItemClickListener,
    SelectActionBar.CallBack,
    FilterRuleAdapter.CallBack {
    override val binding by viewBinding(ActivityFilterRuleBinding::inflate)
    override val viewModel by viewModels<FilterRuleViewModel>()
    private val importRecordKey = "filterRuleRecordKey"
    private val adapter by lazy { FilterRuleAdapter(this, this) }
    private val searchView: SearchView by lazy {
        binding.titleBar.findViewById(R.id.search_view)
    }
    private var groupMenu: SubMenu? = null
    private var filterRuleFlowJob: Job? = null
    private var dataInit = false
    private val editActivity =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) {
                setResult(RESULT_OK)
            }
        }
    private val exportResult = registerForActivityResult(HandleFileContract()) {
        it.uri?.let { uri ->
            alert(R.string.export_success) {
                if (uri.toString().isAbsUrl()) {
                    setMessage(DirectLinkUpload.getSummary())
                }
                val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                    editView.hint = getString(R.string.path)
                    editView.setText(uri.toString())
                }
                customView { alertBinding.root }
                okButton {
                    sendToClip(uri.toString())
                }
            }
        }
    }
    private val qrCodeResult = registerForActivityResult(QrCodeResult()) {
        it ?: return@registerForActivityResult
        showDialogFragment(
            ImportFilterRuleDialog(it)
        )
    }
    private val importDoc = registerForActivityResult(HandleFileContract()) {
        kotlin.runCatching {
            it.uri?.readText(this)?.let {
                showDialogFragment(
                    ImportFilterRuleDialog(it)
                )
            }
        }.onFailure {
            toastOnUi("readTextError:${it.localizedMessage}")
        }
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        initRecyclerView()
        initSearchView()
        initSelectActionView()
        viewModel.initData(intent) {
            observeFilterRuleData()
        }
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            currentFocus?.let {
                if (it.shouldHideSoftInput(ev)) {
                    it.clearFocus()
                    it.hideSoftInput()
                }
            }
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun onCompatCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.filter_rule, menu)
        return super.onCompatCreateOptionsMenu(menu)
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        groupMenu = menu.findItem(R.id.menu_group)?.subMenu
        return super.onPrepareOptionsMenu(menu)
    }

    private fun initRecyclerView() {
        binding.recyclerView.setEdgeEffectColor(primaryColor)
        binding.recyclerView.layoutManager = LinearLayoutManager(this)
        binding.recyclerView.adapter = adapter
        binding.recyclerView.addItemDecoration(VerticalDivider(this))
        val itemTouchCallback = ItemTouchCallback(adapter)
        itemTouchCallback.isCanDrag = true
        val dragSelectTouchHelper: DragSelectTouchHelper =
            DragSelectTouchHelper(adapter.dragSelectCallback).setSlideArea(16, 50)
        dragSelectTouchHelper.attachToRecyclerView(binding.recyclerView)
        // When this page is opened, it is in selection mode
        dragSelectTouchHelper.activeSlideSelect()

        // Note: need judge selection first, so add ItemTouchHelper after it.
        ItemTouchHelper(itemTouchCallback).attachToRecyclerView(binding.recyclerView)
    }

    private fun initSearchView() {
        searchView.applyTint(primaryTextColor)
        searchView.queryHint = getString(R.string.filter_search)
        searchView.setOnQueryTextListener(this)
    }

    override fun selectAll(selectAll: Boolean) {
        if (selectAll) {
            adapter.selectAll()
        } else {
            adapter.revertSelection()
        }
    }

    override fun revertSelection() {
        adapter.revertSelection()
    }

    override fun onClickSelectBarMainAction() {
        alert(titleResource = R.string.draw, messageResource = R.string.sure_del) {
            yesButton { viewModel.delSelection(adapter.selection) }
            noButton()
        }
    }

    private fun initSelectActionView() {
        binding.selectActionBar.setMainActionText(R.string.delete)
        binding.selectActionBar.inflateMenu(R.menu.filter_rule_sel)
        binding.selectActionBar.setOnMenuItemClickListener(this)
        binding.selectActionBar.setCallBack(this)
    }

    private fun observeFilterRuleData(searchKey: String? = null) {
        dataInit = false
        filterRuleFlowJob?.cancel()
        filterRuleFlowJob = lifecycleScope.launch {
            when {
                searchKey.isNullOrEmpty() -> {
                    appDb.filterRuleDao.flowAll()
                }

                else -> {
                    appDb.filterRuleDao.flowSearch("%$searchKey%")
                }
            }.catch {
                AppLog.put("过滤器规则管理界面更新数据出错", it)
            }.flowOn(IO).conflate().collect {
                if (dataInit) {
                    setResult(RESULT_OK)
                }
                adapter.setItems(it, adapter.diffItemCallBack)
                dataInit = true
                delay(100)
            }
        }
    }

    override fun onCompatOptionsItemSelected(item: MenuItem): Boolean {
        when (item.itemId) {
            R.id.menu_add_filter_rule ->
                editActivity.launch(FilterEditActivity.startIntent(this))
            R.id.menu_del_selection -> viewModel.delSelection(adapter.selection)
            R.id.menu_import_onLine -> showImportDialog()
            R.id.menu_import_local -> importDoc.launch {
                mode = HandleFileContract.FILE
                allowExtensions = arrayOf("txt", "json")
            }
            R.id.menu_import_qr -> qrCodeResult.launch()
            R.id.menu_help -> showHelp("filterRuleHelp")
        }
        return super.onCompatOptionsItemSelected(item)
    }

    override fun onMenuItemClick(item: MenuItem?): Boolean {
        when (item?.itemId) {
            R.id.menu_enable_selection -> viewModel.enableSelection(adapter.selection)
            R.id.menu_disable_selection -> viewModel.disableSelection(adapter.selection)
            R.id.menu_export_selection -> exportResult.launch {
                mode = HandleFileContract.EXPORT
                fileData = HandleFileContract.FileData(
                    "exportFilterRule.json",
                    GSON.toJson(adapter.selection).toByteArray(),
                    "application/json"
                )
            }
        }
        return false
    }

    override fun onQueryTextChange(newText: String?): Boolean {
        observeFilterRuleData(newText)
        return false
    }

    override fun onQueryTextSubmit(query: String?): Boolean {
        return false
    }

    override fun upCountView() {
        binding.selectActionBar.upCountView(
            adapter.selection.size,
            adapter.itemCount
        )
    }

    override fun update(vararg rule: FilterRule) {
        setResult(RESULT_OK)
        viewModel.update(*rule)
    }

    override fun delete(rule: FilterRule) {
        alert(R.string.draw) {
            setMessage(getString(R.string.sure_del) + "\n" + rule.name)
            noButton()
            yesButton {
                setResult(RESULT_OK)
                viewModel.delete(rule)
            }
        }
    }

    override fun edit(rule: FilterRule) {
        setResult(RESULT_OK)
        editActivity.launch(FilterEditActivity.startIntent(this, rule.id))
    }

    override fun toTop(rule: FilterRule) {
        setResult(RESULT_OK)
        viewModel.toTop(rule)
    }

    override fun toBottom(rule: FilterRule) {
        setResult(RESULT_OK)
        viewModel.toBottom(rule)
    }

    override fun upOrder() {
        setResult(RESULT_OK)
        viewModel.upOrder()
    }

    @SuppressLint("InflateParams")
    private fun showImportDialog() {
        val aCache = ACache.get(cacheDir = false)
        val cacheUrls: MutableList<String> = aCache
            .getAsString(importRecordKey)
            ?.splitNotBlank(",")
            ?.toMutableList() ?: mutableListOf()
        alert(titleResource = R.string.import_on_line) {
            val alertBinding = DialogEditTextBinding.inflate(layoutInflater).apply {
                editView.hint = "url"
                editView.setFilterValues(cacheUrls)
                editView.delCallBack = {
                    cacheUrls.remove(it)
                    aCache.put(importRecordKey, cacheUrls.joinToString(","))
                }
            }
            customView { alertBinding.root }
            okButton {
                val text = alertBinding.editView.text?.toString()
                text?.let {
                    if (it.isAbsUrl() && !cacheUrls.contains(it)) {
                        cacheUrls.add(0, it)
                        aCache.put(importRecordKey, cacheUrls.joinToString(","))
                    }
                    showDialogFragment(
                        ImportFilterRuleDialog(it)
                    )
                }
            }
            cancelButton()
        }
    }
}