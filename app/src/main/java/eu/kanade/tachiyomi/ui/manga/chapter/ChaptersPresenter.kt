package eu.kanade.tachiyomi.ui.manga.chapter

import android.os.Bundle
import eu.kanade.tachiyomi.data.database.DatabaseHelper
import eu.kanade.tachiyomi.data.database.models.Chapter
import eu.kanade.tachiyomi.data.database.models.Manga
import eu.kanade.tachiyomi.data.download.DownloadManager
import eu.kanade.tachiyomi.data.download.DownloadService
import eu.kanade.tachiyomi.data.download.model.Download
import eu.kanade.tachiyomi.data.preference.PreferencesHelper
import eu.kanade.tachiyomi.data.source.Source
import eu.kanade.tachiyomi.data.source.SourceManager
import eu.kanade.tachiyomi.ui.base.presenter.BasePresenter
import eu.kanade.tachiyomi.ui.manga.MangaEvent
import eu.kanade.tachiyomi.ui.manga.info.ChapterCountEvent
import eu.kanade.tachiyomi.util.SharedData
import eu.kanade.tachiyomi.util.syncChaptersWithSource
import rx.Observable
import rx.android.schedulers.AndroidSchedulers
import rx.schedulers.Schedulers
import rx.subjects.PublishSubject
import timber.log.Timber
import uy.kohesive.injekt.injectLazy

/**
 * Presenter of [ChaptersFragment].
 */
class ChaptersPresenter : BasePresenter<ChaptersFragment>() {

    /**
     * Database helper.
     */
    val db: DatabaseHelper by injectLazy()

    /**
     * Source manager.
     */
    val sourceManager: SourceManager by injectLazy()

    /**
     * Preferences.
     */
    val preferences: PreferencesHelper by injectLazy()

    /**
     * Downloads manager.
     */
    val downloadManager: DownloadManager by injectLazy()

    /**
     * Active manga.
     */
    lateinit var manga: Manga
        private set

    /**
     * Source of the manga.
     */
    lateinit var source: Source
        private set

    /**
     * List of chapters of the manga. It's always unfiltered and unsorted.
     */
    var chapters: List<ChapterModel> = emptyList()
        private set

    /**
     * Subject of list of chapters to allow updating the view without going to DB.
     */
    val chaptersSubject by lazy { PublishSubject.create<List<ChapterModel>>() }

    /**
     * Whether the chapter list has been requested to the source.
     */
    var hasRequested = false
        private set

    companion object {
        /**
         * Id of the restartable which sends a filtered and ordered list of chapters to the view.
         */
        private const val GET_CHAPTERS = 1

        /**
         * Id of the restartable which requests an updated list of chapters to the source.
         */
        private const val FETCH_CHAPTERS = 2

        /**
         * Id of the restartable which listens for download status changes.
         */
        private const val CHAPTER_STATUS_CHANGES = 3
    }

    override fun onCreate(savedState: Bundle?) {
        super.onCreate(savedState)

        startableLatestCache(GET_CHAPTERS,
                // On each subject emission, apply filters and sort then update the view.
                { chaptersSubject
                        .flatMap { applyChapterFilters(it) }
                        .observeOn(AndroidSchedulers.mainThread()) },
                { view, chapters -> view.onNextChapters(chapters) })

        startableFirst(FETCH_CHAPTERS,
                { getRemoteChaptersObservable() },
                { view, result -> view.onFetchChaptersDone() },
                { view, error -> view.onFetchChaptersError(error) })

        startableLatestCache(CHAPTER_STATUS_CHANGES,
                { getChapterStatusObservable() },
                { view, download -> view.onChapterStatusChange(download) },
                { view, error -> Timber.e(error.cause, error.message) })

        // Find the active manga from the shared data or return.
        manga = SharedData.get(MangaEvent::class.java)?.manga ?: return
        Observable.just(manga)
                .subscribeLatestCache({ view, manga -> view.onNextManga(manga) })

        // Find the source for this manga.
        source = sourceManager.get(manga.source)!!

        // Prepare the publish subject.
        start(GET_CHAPTERS)

        // Add the subscription that retrieves the chapters from the database, keeps subscribed to
        // changes, and sends the list of chapters to the publish subject.
        add(db.getChapters(manga).asRxObservable()
                .map { chapters ->
                    // Convert every chapter to a model.
                    chapters.map { it.toModel() }
                }
                .doOnNext { chapters ->
                    // Store the last emission
                    this.chapters = chapters

                    // Listen for download status changes
                    start(CHAPTER_STATUS_CHANGES)

                    // Emit the number of chapters to the info tab.
                    SharedData.get(ChapterCountEvent::class.java)?.emit(chapters.size)
                }
                .subscribe { chaptersSubject.onNext(it) })
    }

    /**
     * Converts a chapter from the database to an extended model, allowing to store new fields.
     */
    private fun Chapter.toModel(): ChapterModel {
        // Create the model object.
        val model = ChapterModel(this)

        // Find an active download for this chapter.
        val download = downloadManager.queue.find { it.chapter.id == id }

        if (download != null) {
            // If there's an active download, assign it.
            model.download = download
        } else {
            // Otherwise ask the manager if the chapter is downloaded and assign it to the status.
            model.status = if (downloadManager.isChapterDownloaded(source, manga, this))
                Download.DOWNLOADED
            else
                Download.NOT_DOWNLOADED
        }
        return model
    }

    /**
     * Requests an updated list of chapters from the source.
     */
    fun fetchChaptersFromSource() {
        hasRequested = true
        start(FETCH_CHAPTERS)
    }

    /**
     * Updates the UI after applying the filters.
     */
    private fun refreshChapters() {
        chaptersSubject.onNext(chapters)
    }

    /**
     * Returns an observable that updates the chapter list with the latest from the source.
     */
    fun getRemoteChaptersObservable() = source.fetchChapterList(manga)
            .subscribeOn(Schedulers.io())
            .map { syncChaptersWithSource(db, it, manga, source) }
            .observeOn(AndroidSchedulers.mainThread())

    /**
     * Returns an observable that listens to download queue status changes.
     */
    fun getChapterStatusObservable() = downloadManager.queue.getStatusObservable()
            .observeOn(AndroidSchedulers.mainThread())
            .filter { download -> download.manga.id == manga.id }
            .doOnNext { onDownloadStatusChange(it) }

    /**
     * Applies the view filters to the list of chapters obtained from the database.
     *
     * @param chapters the list of chapters from the database
     * @return an observable of the list of chapters filtered and sorted.
     */
    private fun applyChapterFilters(chapters: List<ChapterModel>): Observable<List<ChapterModel>> {
        var observable = Observable.from(chapters).subscribeOn(Schedulers.io())
        if (onlyUnread()) {
            observable = observable.filter { !it.read }
        }
        if (onlyDownloaded()) {
            observable = observable.filter { it.isDownloaded }
        }
        val sortFunction: (Chapter, Chapter) -> Int = when (manga.sorting) {
            Manga.SORTING_SOURCE -> when (sortDescending()) {
                true  -> { c1, c2 -> c1.source_order.compareTo(c2.source_order) }
                false -> { c1, c2 -> c2.source_order.compareTo(c1.source_order) }
            }
            Manga.SORTING_NUMBER -> when (sortDescending()) {
                true  -> { c1, c2 -> c2.chapter_number.compareTo(c1.chapter_number) }
                false -> { c1, c2 -> c1.chapter_number.compareTo(c2.chapter_number) }
            }
            else -> throw NotImplementedError("Unimplemented sorting method")
        }
        return observable.toSortedList(sortFunction)
    }

    /**
     * Called when a download for the active manga changes status.
     *
     * @param download the download whose status changed.
     */
    fun onDownloadStatusChange(download: Download) {
        // Assign the download to the model object.
        if (download.status == Download.QUEUE) {
            chapters.find { it.id == download.chapter.id }?.let {
                if (it.download == null) {
                    it.download = download
                }
            }
        }

        // Force UI update if downloaded filter active and download finished.
        if (onlyDownloaded() && download.status == Download.DOWNLOADED)
            refreshChapters()
    }

    /**
     * Returns the next unread chapter or null if everything is read.
     */
    fun getNextUnreadChapter(): ChapterModel? {
        return chapters.sortedByDescending { it.source_order }.find { !it.read }
    }

    /**
     * Mark the selected chapter list as read/unread.
     *
     * @param selectedChapters the list of selected chapters.
     * @param read whether to mark chapters as read or unread.
     */
    fun markChaptersRead(selectedChapters: List<ChapterModel>, read: Boolean) {
        Observable.from(selectedChapters)
                .doOnNext { chapter ->
                    chapter.read = read
                    if (!read) {
                        chapter.last_page_read = 0
                    }
                }
                .toList()
                .flatMap { db.updateChaptersProgress(it).asRxObservable() }
                .subscribeOn(Schedulers.io())
                .subscribe()
    }

    /**
     * Mark the previous chapters to the selected one as read.
     *
     * @param chapter the selected chapter.
     */
    fun markPreviousChaptersAsRead(chapter: ChapterModel) {
        Observable.from(chapters)
                .filter { it.isRecognizedNumber && it.chapter_number < chapter.chapter_number }
                .doOnNext { it.read = true }
                .toList()
                .flatMap { db.updateChaptersProgress(it).asRxObservable() }
                .subscribe()
    }

    /**
     * Downloads the given list of chapters with the manager.
     *
     * @param chapters the list of chapters to download.
     */
    fun downloadChapters(chapters: List<ChapterModel>) {
        DownloadService.start(context)
        downloadManager.downloadChapters(manga, chapters)
    }

    /**
     * Deletes the given list of chapter.
     *
     * @param chapters the list of chapters to delete.
     */
    fun deleteChapters(chapters: List<ChapterModel>) {
        val wasRunning = downloadManager.isRunning
        if (wasRunning) {
            DownloadService.stop(context)
        }
        Observable.from(chapters)
                .doOnNext { deleteChapter(it) }
                .toList()
                .doOnNext { if (onlyDownloaded()) refreshChapters() }
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribeFirst({ view, result ->
                    view.onChaptersDeleted()
                    if (wasRunning) {
                        DownloadService.start(context)
                    }
                }, { view, error ->
                    view.onChaptersDeletedError(error)
                })
    }

    /**
     * Deletes a chapter from disk. This method is called in a background thread.
     *
     * @param chapter the chapter to delete.
     */
    private fun deleteChapter(chapter: ChapterModel) {
        downloadManager.queue.del(chapter)
        downloadManager.deleteChapter(source, manga, chapter)
        chapter.status = Download.NOT_DOWNLOADED
        chapter.download = null
    }

    /**
     * Reverses the sorting and requests an UI update.
     */
    fun revertSortOrder() {
        manga.setChapterOrder(if (sortDescending()) Manga.SORT_ASC else Manga.SORT_DESC)
        db.updateFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Sets the read filter and requests an UI update.
     *
     * @param onlyUnread whether to display only unread chapters or all chapters.
     */
    fun setReadFilter(onlyUnread: Boolean) {
        manga.readFilter = if (onlyUnread) Manga.SHOW_UNREAD else Manga.SHOW_ALL
        db.updateFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Sets the download filter and requests an UI update.
     *
     * @param onlyDownloaded whether to display only downloaded chapters or all chapters.
     */
    fun setDownloadedFilter(onlyDownloaded: Boolean) {
        manga.downloadedFilter = if (onlyDownloaded) Manga.SHOW_DOWNLOADED else Manga.SHOW_ALL
        db.updateFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Removes all filters and requests an UI update.
     */
    fun removeFilters() {
        manga.readFilter = Manga.SHOW_ALL
        manga.downloadedFilter = Manga.SHOW_ALL
        db.updateFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Sets the active display mode.
     *
     * @param mode the mode to set.
     */
    fun setDisplayMode(mode: Int) {
        manga.displayMode = mode
        db.updateFlags(manga).executeAsBlocking()
    }

    /**
     * Sets the sorting method and requests an UI update.
     *
     * @param sort the sorting mode.
     */
    fun setSorting(sort: Int) {
        manga.sorting = sort
        db.updateFlags(manga).executeAsBlocking()
        refreshChapters()
    }

    /**
     * Whether the display only downloaded filter is enabled.
     */
    fun onlyDownloaded(): Boolean {
        return manga.downloadedFilter == Manga.SHOW_DOWNLOADED
    }

    /**
     * Whether the display only unread filter is enabled.
     */
    fun onlyUnread(): Boolean {
        return manga.readFilter == Manga.SHOW_UNREAD
    }

    /**
     * Whether the sorting method is descending or ascending.
     */
    fun sortDescending(): Boolean {
        return manga.sortDescending()
    }

}
