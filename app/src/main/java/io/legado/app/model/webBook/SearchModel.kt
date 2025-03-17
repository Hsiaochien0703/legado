package io.legado.app.model.webBook

import io.legado.app.constant.AppConst
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.BookSourcePart
import io.legado.app.data.entities.SearchBook
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.config.AppConfig
import io.legado.app.ui.book.search.SearchScope
import io.legado.app.utils.FileDocFilter
import io.legado.app.utils.FilterUtils
import io.legado.app.utils.getPrefBoolean
import io.legado.app.utils.mapParallelSafe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import splitties.init.appCtx
import java.util.concurrent.Executors
import kotlin.coroutines.coroutineContext
import kotlin.math.min

class SearchModel(private val scope: CoroutineScope, private val callBack: CallBack) {
    val threadCount = AppConfig.threadCount
    private var searchPool: ExecutorCoroutineDispatcher? = null
    private var mSearchId = 0L
    private var searchPage = 1
    private var searchKey: String = ""
    private var bookSourceParts = emptyList<BookSourcePart>()
    private var searchBooks = arrayListOf<SearchBook>()
    private var filterBooks = arrayListOf<SearchBook>()
    private var searchJob: Job? = null


    private fun initSearchPool() {
        searchPool?.close()
        searchPool = Executors
            .newFixedThreadPool(min(threadCount, AppConst.MAX_THREAD)).asCoroutineDispatcher()
    }

    fun search(searchId: Long, key: String) {
        if (searchId != mSearchId) {
            if (key.isEmpty()) {
                return
            }
            searchKey = key
            if (mSearchId != 0L) {
                close()
            }
            searchBooks.clear()
            bookSourceParts = callBack.getSearchScope().getBookSourceParts()
            if (bookSourceParts.isEmpty()) {
                callBack.onSearchCancel(NoStackTraceException("启用书源为空"))
                return
            }
            mSearchId = searchId
            searchPage = 1
            initSearchPool()
        } else {
            searchPage++
        }
        startSearch()
    }

    private fun startSearch() {
        val precision = appCtx.getPrefBoolean(PreferKey.precisionSearch)
        var hasMore = false
        searchJob = scope.launch(searchPool!!) {
            flow {
                for (bs in bookSourceParts) {
                    bs.getBookSource()?.let {
                        emit(it)
                    }
                }
            }.onStart {
                callBack.onSearchStart()
            }.mapParallelSafe(threadCount) {
                withTimeout(30000L) {
                    WebBook.searchBookAwait(it, searchKey, searchPage)
                }
            }.onEach { items ->
                val pair = partition(items)
                val filter = pair.first
                val titems = pair.second
                for (book in titems) {
                    book.releaseHtmlData()
                }
                hasMore = hasMore || titems.isNotEmpty()
                appDb.searchBookDao.insert(*titems.toTypedArray())
                mergeFilterItems(filter,precision)
                mergeSearchItems(titems, precision)
                currentCoroutineContext().ensureActive()
                callBack.onSearchSuccess(searchBooks,filterBooks)
            }.onCompletion {
                if (it == null) callBack.onSearchFinish(searchBooks.isEmpty(), hasMore)
            }.catch {
                AppLog.put("书源搜索出错\n${it.localizedMessage}", it)
            }.collect()
        }
    }

    private suspend inline fun addCondition(list: ArrayList<SearchBook>, searchBook: SearchBook){
        var hasSame = false
        list.forEach { book ->
            coroutineContext.ensureActive()
            if (book.name == searchBook.name && book.author == searchBook.author) {
                book.addOrigin(searchBook.origin)
                hasSame = true
            }
        }
        if (!hasSame) {
            list.add(searchBook)
        }
    }

    private suspend fun mergeSearchItems(newDataS: List<SearchBook>, precision: Boolean){
        val items = mergeItemsR(searchBooks,newDataS,precision)
        if (items != null){
            searchBooks = items
        }
    }

    private suspend fun mergeFilterItems(newDataS: List<SearchBook>, precision: Boolean){
        val items = mergeItemsR(filterBooks,newDataS,precision)
        if (items != null){
            filterBooks = items
        }
    }


    private suspend fun mergeItemsR(list: ArrayList<SearchBook>,newDataS: List<SearchBook>, precision: Boolean): ArrayList<SearchBook>? {
        if (newDataS.isEmpty()) return null
        val copyData = ArrayList(list)
        val equalData = arrayListOf<SearchBook>()
        val containsData = arrayListOf<SearchBook>()
        val otherData = arrayListOf<SearchBook>()
        copyData.forEach {
            coroutineContext.ensureActive()
            if (it.name == searchKey || it.author == searchKey) {
                equalData.add(it)
            } else if (it.name.contains(searchKey) || it.author.contains(searchKey)) {
                containsData.add(it)
            } else {
                otherData.add(it)
            }
        }
        newDataS.forEach { nBook ->
            coroutineContext.ensureActive()
            if (nBook.name == searchKey || nBook.author == searchKey) {
                addCondition(equalData, nBook)
            } else if (nBook.name.contains(searchKey) || nBook.author.contains(searchKey)) {
                addCondition(containsData, nBook)
            } else if (!precision) {
                addCondition(containsData, nBook)
            }
        }
        coroutineContext.ensureActive()
        equalData.sortByDescending { it.origins.size }
        equalData.addAll(containsData.sortedByDescending { it.origins.size })
        if (!precision) {
            equalData.addAll(otherData)
        }
        coroutineContext.ensureActive()
        return equalData
    }

    private fun partition(lsearchBooks: ArrayList<SearchBook>): Pair<List<SearchBook>, List<SearchBook>> {
        return lsearchBooks.partition { FilterUtils.test(it.name) }
    }

    fun cancelSearch() {
        close()
        callBack.onSearchCancel()
    }

    fun close() {
        searchJob?.cancel()
        searchPool?.close()
        searchPool = null
        mSearchId = 0L
    }

    interface CallBack {
        fun getSearchScope(): SearchScope
        fun onSearchStart()
        fun onSearchSuccess(searchBooks: List<SearchBook>,filterBooks:List<SearchBook>)
        fun onSearchFinish(isEmpty: Boolean, hasMore: Boolean)
        fun onSearchCancel(exception: Throwable? = null)
    }

}