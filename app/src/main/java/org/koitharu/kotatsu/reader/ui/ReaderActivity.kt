package org.koitharu.kotatsu.reader.ui

import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.transition.Fade
import android.transition.Slide
import android.transition.TransitionManager
import android.transition.TransitionSet
import android.view.Gravity
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.graphics.Insets
import androidx.core.view.OnApplyWindowInsetsListener
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isGone
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koitharu.kotatsu.BuildConfig
import org.koitharu.kotatsu.R
import org.koitharu.kotatsu.base.domain.MangaIntent
import org.koitharu.kotatsu.base.ui.BaseFullscreenActivity
import org.koitharu.kotatsu.bookmarks.domain.Bookmark
import org.koitharu.kotatsu.core.exceptions.resolve.ExceptionResolver
import org.koitharu.kotatsu.core.model.parcelable.ParcelableManga
import org.koitharu.kotatsu.core.prefs.ReaderMode
import org.koitharu.kotatsu.databinding.ActivityReaderBinding
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaChapter
import org.koitharu.kotatsu.parsers.model.MangaPage
import org.koitharu.kotatsu.reader.ui.config.PageSwitchTimer
import org.koitharu.kotatsu.reader.ui.config.ReaderConfigBottomSheet
import org.koitharu.kotatsu.reader.ui.pager.ReaderUiState
import org.koitharu.kotatsu.reader.ui.thumbnails.OnPageSelectListener
import org.koitharu.kotatsu.reader.ui.thumbnails.PagesThumbnailsSheet
import org.koitharu.kotatsu.settings.SettingsActivity
import org.koitharu.kotatsu.utils.GridTouchHelper
import org.koitharu.kotatsu.utils.IdlingDetector
import org.koitharu.kotatsu.utils.ShareHelper
import org.koitharu.kotatsu.utils.ext.assistedViewModels
import org.koitharu.kotatsu.utils.ext.getDisplayMessage
import org.koitharu.kotatsu.utils.ext.getParcelableExtraCompat
import org.koitharu.kotatsu.utils.ext.hasGlobalPoint
import org.koitharu.kotatsu.utils.ext.isReportable
import org.koitharu.kotatsu.utils.ext.observeWithPrevious
import org.koitharu.kotatsu.utils.ext.postDelayed
import org.koitharu.kotatsu.utils.ext.report
import org.koitharu.kotatsu.utils.ext.setValueRounded
import java.util.concurrent.TimeUnit
import javax.inject.Inject

@AndroidEntryPoint
class ReaderActivity :
	BaseFullscreenActivity<ActivityReaderBinding>(),
	ChaptersBottomSheet.OnChapterChangeListener,
	GridTouchHelper.OnGridTouchListener,
	OnPageSelectListener,
	ReaderConfigBottomSheet.Callback,
	ReaderControlDelegate.OnInteractionListener,
	OnApplyWindowInsetsListener,
	IdlingDetector.Callback {

	@Inject
	lateinit var viewModelFactory: ReaderViewModel.Factory

	private val idlingDetector = IdlingDetector(TimeUnit.SECONDS.toMillis(10), this)

	val viewModel by assistedViewModels {
		viewModelFactory.create(
			intent = MangaIntent(intent),
			initialState = intent?.getParcelableExtraCompat(EXTRA_STATE),
			preselectedBranch = intent?.getStringExtra(EXTRA_BRANCH),
		)
	}

	override var pageSwitchDelay: Float
		get() = pageSwitchTimer.delaySec
		set(value) {
			pageSwitchTimer.delaySec = value
		}

	override val readerMode: ReaderMode?
		get() = readerManager.currentMode

	private lateinit var pageSwitchTimer: PageSwitchTimer
	private lateinit var touchHelper: GridTouchHelper
	private lateinit var controlDelegate: ReaderControlDelegate
	private var gestureInsets: Insets = Insets.NONE
	private lateinit var readerManager: ReaderManager
	private val hideUiRunnable = Runnable { setUiIsVisible(false) }

	override fun onCreate(savedInstanceState: Bundle?) {
		super.onCreate(savedInstanceState)
		setContentView(ActivityReaderBinding.inflate(layoutInflater))
		readerManager = ReaderManager(supportFragmentManager, R.id.container)
		supportActionBar?.setDisplayHomeAsUpEnabled(true)
		touchHelper = GridTouchHelper(this, this)
		pageSwitchTimer = PageSwitchTimer(this, this)
		controlDelegate = ReaderControlDelegate(settings, this, this)
		binding.toolbarBottom.setOnMenuItemClickListener(::onOptionsItemSelected)
		binding.slider.setLabelFormatter(PageLabelFormatter())
		ReaderSliderListener(this, viewModel).attachToSlider(binding.slider)
		insetsDelegate.interceptingWindowInsetsListener = this
		idlingDetector.bindToLifecycle(this)

		viewModel.onError.observe(this, this::onError)
		viewModel.readerMode.observe(this, this::onInitReader)
		viewModel.onPageSaved.observe(this, this::onPageSaved)
		viewModel.uiState.observeWithPrevious(this, this::onUiStateChanged)
		viewModel.isLoading.observe(this, this::onLoadingStateChanged)
		viewModel.content.observe(this) {
			onLoadingStateChanged(viewModel.isLoading.value == true)
		}
		viewModel.isScreenshotsBlockEnabled.observe(this, this::setWindowSecure)
		viewModel.isInfoBarEnabled.observe(this, ::onReaderBarChanged)
		viewModel.isBookmarkAdded.observe(this, this::onBookmarkStateChanged)
		viewModel.onShowToast.observe(this) { msgId ->
			Snackbar.make(binding.container, msgId, Snackbar.LENGTH_SHORT)
				.setAnchorView(binding.appbarBottom)
				.show()
		}
	}

	override fun onUserInteraction() {
		super.onUserInteraction()
		pageSwitchTimer.onUserInteraction()
		idlingDetector.onUserInteraction()
	}

	override fun onIdle() {
		viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
	}

	private fun onInitReader(mode: ReaderMode) {
		if (readerManager.currentMode != mode) {
			readerManager.replace(mode)
		}
		if (binding.appbarTop.isVisible) {
			lifecycle.postDelayed(hideUiRunnable, TimeUnit.SECONDS.toMillis(1))
		}
	}

	override fun onCreateOptionsMenu(menu: Menu): Boolean {
		menuInflater.inflate(R.menu.opt_reader_top, menu)
		return super.onCreateOptionsMenu(menu)
	}

	override fun onOptionsItemSelected(item: MenuItem): Boolean {
		when (item.itemId) {
			R.id.action_settings -> {
				startActivity(SettingsActivity.newReaderSettingsIntent(this))
			}

			R.id.action_chapters -> {
				ChaptersBottomSheet.show(
					supportFragmentManager,
					viewModel.manga?.chapters.orEmpty(),
					viewModel.getCurrentState()?.chapterId ?: 0L,
				)
			}

			R.id.action_pages_thumbs -> {
				val pages = viewModel.getCurrentChapterPages()
				if (!pages.isNullOrEmpty()) {
					PagesThumbnailsSheet.show(
						supportFragmentManager,
						pages,
						title?.toString().orEmpty(),
						readerManager.currentReader?.getCurrentState()?.page ?: -1,
					)
				} else {
					return false
				}
			}

			R.id.action_bookmark -> {
				if (viewModel.isBookmarkAdded.value == true) {
					viewModel.removeBookmark()
				} else {
					viewModel.addBookmark()
				}
			}

			R.id.action_options -> {
				viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
				val currentMode = readerManager.currentMode ?: return false
				ReaderConfigBottomSheet.show(supportFragmentManager, currentMode)
			}

			else -> return super.onOptionsItemSelected(item)
		}
		return true
	}

	private fun onLoadingStateChanged(isLoading: Boolean) {
		val hasPages = !viewModel.content.value?.pages.isNullOrEmpty()
		binding.layoutLoading.isVisible = isLoading && !hasPages
		if (isLoading && hasPages) {
			binding.toastView.show(R.string.loading_)
		} else {
			binding.toastView.hide()
		}
		val menu = binding.toolbarBottom.menu
		menu.findItem(R.id.action_bookmark).isVisible = hasPages
		menu.findItem(R.id.action_pages_thumbs).isVisible = hasPages
	}

	private fun onError(e: Throwable) {
		val listener = ErrorDialogListener(e)
		val dialog = MaterialAlertDialogBuilder(this)
			.setTitle(R.string.error_occurred)
			.setMessage(e.getDisplayMessage(resources))
			.setNegativeButton(R.string.close, listener)
			.setOnCancelListener(listener)
		val resolveTextId = ExceptionResolver.getResolveStringId(e)
		if (resolveTextId != 0) {
			dialog.setPositiveButton(resolveTextId, listener)
		} else if (e.isReportable()) {
			dialog.setPositiveButton(R.string.report, listener)
		}
		dialog.show()
	}

	override fun onGridTouch(area: Int) {
		controlDelegate.onGridTouch(area, binding.container)
	}

	override fun onProcessTouch(rawX: Int, rawY: Int): Boolean {
		return if (
			rawX <= gestureInsets.left ||
			rawY <= gestureInsets.top ||
			rawX >= binding.root.width - gestureInsets.right ||
			rawY >= binding.root.height - gestureInsets.bottom ||
			binding.appbarTop.hasGlobalPoint(rawX, rawY) ||
			binding.appbarBottom?.hasGlobalPoint(rawX, rawY) == true
		) {
			false
		} else {
			val touchables = window.peekDecorView()?.touchables
			touchables?.none { it.hasGlobalPoint(rawX, rawY) } ?: true
		}
	}

	override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
		touchHelper.dispatchTouchEvent(ev)
		return super.dispatchTouchEvent(ev)
	}

	override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
		return controlDelegate.onKeyDown(keyCode, event) || super.onKeyDown(keyCode, event)
	}

	override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
		return controlDelegate.onKeyUp(keyCode, event) || super.onKeyUp(keyCode, event)
	}

	override fun onChapterChanged(chapter: MangaChapter) {
		viewModel.switchChapter(chapter.id)
	}

	override fun onPageSelected(page: MangaPage) {
		lifecycleScope.launch(Dispatchers.Default) {
			val pages = viewModel.content.value?.pages ?: return@launch
			val index = pages.indexOfFirst { it.id == page.id }
			if (index != -1) {
				withContext(Dispatchers.Main) {
					readerManager.currentReader?.switchPageTo(index, true)
				}
			}
		}
	}

	override fun onReaderModeChanged(mode: ReaderMode) {
		viewModel.saveCurrentState(readerManager.currentReader?.getCurrentState())
		viewModel.switchMode(mode)
	}

	private fun onPageSaved(uri: Uri?) {
		if (uri != null) {
			Snackbar.make(binding.container, R.string.page_saved, Snackbar.LENGTH_LONG)
				.setAnchorView(binding.appbarBottom)
				.setAction(R.string.share) {
					ShareHelper(this).shareImage(uri)
				}.show()
		} else {
			Snackbar.make(binding.container, R.string.error_occurred, Snackbar.LENGTH_SHORT)
				.setAnchorView(binding.appbarBottom)
				.show()
		}
	}

	private fun setWindowSecure(isSecure: Boolean) {
		if (isSecure) {
			window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
		} else {
			window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
		}
	}

	private fun setUiIsVisible(isUiVisible: Boolean) {
		if (binding.appbarTop.isVisible != isUiVisible) {
			val transition = TransitionSet()
				.setOrdering(TransitionSet.ORDERING_TOGETHER)
				.addTransition(Slide(Gravity.TOP).addTarget(binding.appbarTop))
				.addTransition(Fade().addTarget(binding.infoBar))
			binding.appbarBottom?.let { bottomBar ->
				transition.addTransition(Slide(Gravity.BOTTOM).addTarget(bottomBar))
			}
			TransitionManager.beginDelayedTransition(binding.root, transition)
			binding.appbarTop.isVisible = isUiVisible
			binding.appbarBottom?.isVisible = isUiVisible
			binding.infoBar.isGone = isUiVisible || (viewModel.isInfoBarEnabled.value == false)
			if (isUiVisible) {
				showSystemUI()
			} else {
				hideSystemUI()
			}
		}
	}

	override fun onApplyWindowInsets(v: View, insets: WindowInsetsCompat): WindowInsetsCompat {
		gestureInsets = insets.getInsets(WindowInsetsCompat.Type.systemGestures())
		val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
		binding.appbarTop.updatePadding(
			top = systemBars.top,
			right = systemBars.right,
			left = systemBars.left,
		)
		binding.appbarBottom?.updatePadding(
			bottom = systemBars.bottom,
			right = systemBars.right,
			left = systemBars.left,
		)
		return WindowInsetsCompat.Builder(insets)
			.setInsets(WindowInsetsCompat.Type.systemBars(), Insets.NONE)
			.build()
	}

	override fun onWindowInsetsChanged(insets: Insets) = Unit

	override fun switchPageBy(delta: Int) {
		readerManager.currentReader?.switchPageBy(delta)
	}

	override fun toggleUiVisibility() {
		setUiIsVisible(!binding.appbarTop.isVisible)
	}

	private fun onReaderBarChanged(isBarEnabled: Boolean) {
		binding.infoBar.isVisible = isBarEnabled && binding.appbarTop.isGone
	}

	private fun onBookmarkStateChanged(isAdded: Boolean) {
		val menuItem = binding.toolbarBottom.menu.findItem(R.id.action_bookmark) ?: return
		menuItem.setTitle(if (isAdded) R.string.bookmark_remove else R.string.bookmark_add)
		menuItem.setIcon(if (isAdded) R.drawable.ic_bookmark_added else R.drawable.ic_bookmark)
	}

	private fun onUiStateChanged(uiState: ReaderUiState?, previous: ReaderUiState?) {
		title = uiState?.chapterName ?: uiState?.mangaName ?: getString(R.string.loading_)
		binding.infoBar.update(uiState)
		if (uiState == null) {
			supportActionBar?.subtitle = null
			binding.slider.isVisible = false
			return
		}
		supportActionBar?.subtitle = if (uiState.chapterNumber in 1..uiState.chaptersTotal) {
			getString(R.string.chapter_d_of_d, uiState.chapterNumber, uiState.chaptersTotal)
		} else {
			null
		}
		if (previous?.chapterName != null && uiState.chapterName != previous.chapterName) {
			if (!uiState.chapterName.isNullOrEmpty()) {
				binding.toastView.showTemporary(uiState.chapterName, TOAST_DURATION)
			}
		}
		if (uiState.isSliderAvailable()) {
			binding.slider.valueTo = uiState.totalPages.toFloat() - 1
			binding.slider.setValueRounded(uiState.currentPage.toFloat())
			binding.slider.isVisible = true
		} else {
			binding.slider.isVisible = false
		}
	}

	private inner class ErrorDialogListener(
		private val exception: Throwable,
	) : DialogInterface.OnClickListener, DialogInterface.OnCancelListener {

		override fun onClick(dialog: DialogInterface?, which: Int) {
			if (which == DialogInterface.BUTTON_POSITIVE) {
				dialog?.dismiss()
				if (ExceptionResolver.canResolve(exception)) {
					tryResolve(exception)
				} else {
					exception.report()
				}
			} else {
				onCancel(dialog)
			}
		}

		override fun onCancel(dialog: DialogInterface?) {
			if (viewModel.content.value?.pages.isNullOrEmpty()) {
				finishAfterTransition()
			}
		}

		private fun tryResolve(e: Throwable) {
			lifecycleScope.launch {
				if (exceptionResolver.resolve(e)) {
					viewModel.reload()
				} else {
					onCancel(null)
				}
			}
		}
	}

	companion object {

		const val ACTION_MANGA_READ = "${BuildConfig.APPLICATION_ID}.action.READ_MANGA"
		private const val EXTRA_STATE = "state"
		private const val EXTRA_BRANCH = "branch"
		private const val TOAST_DURATION = 1500L

		fun newIntent(context: Context, manga: Manga): Intent {
			return Intent(context, ReaderActivity::class.java)
				.putExtra(MangaIntent.KEY_MANGA, ParcelableManga(manga, withChapters = true))
		}

		fun newIntent(context: Context, manga: Manga, branch: String?): Intent {
			return Intent(context, ReaderActivity::class.java)
				.putExtra(MangaIntent.KEY_MANGA, ParcelableManga(manga, withChapters = true))
				.putExtra(EXTRA_BRANCH, branch)
		}

		fun newIntent(context: Context, manga: Manga, state: ReaderState?): Intent {
			return Intent(context, ReaderActivity::class.java)
				.putExtra(MangaIntent.KEY_MANGA, ParcelableManga(manga, withChapters = true))
				.putExtra(EXTRA_STATE, state)
		}

		fun newIntent(context: Context, bookmark: Bookmark): Intent {
			val state = ReaderState(bookmark.chapterId, bookmark.page, bookmark.scroll)
			return newIntent(context, bookmark.manga, state)
		}

		fun newIntent(context: Context, mangaId: Long): Intent {
			return Intent(context, ReaderActivity::class.java)
				.putExtra(MangaIntent.KEY_ID, mangaId)
		}
	}
}
