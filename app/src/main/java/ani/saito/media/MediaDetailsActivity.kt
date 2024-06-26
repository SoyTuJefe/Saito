package ani.saito.media

import android.animation.ObjectAnimator
import android.annotation.SuppressLint
import android.content.Intent
import android.os.Bundle
import android.text.SpannableStringBuilder
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.ImageView
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import androidx.core.text.bold
import androidx.core.text.color
import androidx.core.view.marginBottom
import androidx.core.view.updateLayoutParams
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.lifecycleScope
import androidx.viewpager2.adapter.FragmentStateAdapter
import ani.saito.GesturesListener
import ani.saito.R
import ani.saito.Refresh
import ani.saito.ZoomOutPageTransformer
import ani.saito.blurImage
import ani.saito.connections.anilist.Anilist
import ani.saito.copyToClipboard
import ani.saito.databinding.ActivityMediaBinding
import ani.saito.initActivity
import ani.saito.loadImage
import ani.saito.media.anime.AnimeWatchFragment
import ani.saito.media.comments.CommentsFragment
import ani.saito.media.manga.MangaReadFragment
import ani.saito.media.novel.NovelReadFragment
import ani.saito.navBarHeight
import ani.saito.openLinkInBrowser
import ani.saito.others.AndroidBug5497Workaround
import ani.saito.others.ImageViewDialog
import ani.saito.others.getSerialized
import ani.saito.settings.saving.PrefManager
import ani.saito.settings.saving.PrefName
import ani.saito.snackString
import ani.saito.statusBarHeight
import ani.saito.themes.ThemeManager
import com.flaviofaria.kenburnsview.RandomTransitionGenerator
import com.google.android.material.appbar.AppBarLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlin.math.abs


class MediaDetailsActivity : AppCompatActivity(), AppBarLayout.OnOffsetChangedListener {

    lateinit var binding: ActivityMediaBinding
    private val scope = lifecycleScope
    private val model: MediaDetailsViewModel by viewModels()
    lateinit var tabLayout: TripleNavAdapter
    var selected = 0
    var anime = true
    private var adult = false

    @SuppressLint("SetTextI18n", "ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        var media: Media = intent.getSerialized("media") ?: mediaSingleton ?: emptyMedia()
        val id = intent.getIntExtra("mediaId", -1)
        if (id != -1) {
            runBlocking {
                withContext(Dispatchers.IO) {
                    media =
                        Anilist.query.getMedia(id, false) ?: emptyMedia()
                }
            }
        }
        if (media.name == "No media found") {
            snackString(media.name)
            onBackPressedDispatcher.onBackPressed()
            return
        }
        mediaSingleton = null
        ThemeManager(this).applyTheme(MediaSingleton.bitmap)
        MediaSingleton.bitmap = null

        binding = ActivityMediaBinding.inflate(layoutInflater)
        setContentView(binding.root)
        screenWidth = resources.displayMetrics.widthPixels.toFloat()

        val isVertical = resources.configuration.orientation
        //Ui init

        initActivity(this)
        binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin += navBarHeight }
        val oldMargin = binding.mediaViewPager.marginBottom
        AndroidBug5497Workaround.assistActivity(this) {
            if (it) {
                binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = 0
                }
                binding.mediaTabContainer.visibility = View.GONE
            } else {
                binding.mediaViewPager.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                    bottomMargin = oldMargin
                }
                binding.mediaTabContainer.visibility = View.VISIBLE
            }
        }
        binding.mediaBanner.updateLayoutParams { height += statusBarHeight }
        binding.mediaBannerNoKen.updateLayoutParams { height += statusBarHeight }
        binding.mediaClose.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.incognito.updateLayoutParams<ViewGroup.MarginLayoutParams> { topMargin += statusBarHeight }
        binding.mediaCollapsing.minimumHeight = statusBarHeight

        binding.mediaTitle.isSelected = true

        mMaxScrollSize = binding.mediaAppBar.totalScrollRange
        binding.mediaAppBar.addOnOffsetChangedListener(this)

        binding.mediaClose.setOnClickListener {
            onBackPressedDispatcher.onBackPressed()
        }

        val bannerAnimations: Boolean = PrefManager.getVal(PrefName.BannerAnimations)
        if (bannerAnimations) {
            val adi = AccelerateDecelerateInterpolator()
            val generator = RandomTransitionGenerator(
                (10000 + 15000 * ((PrefManager.getVal(PrefName.AnimationSpeed) as Float))).toLong(),
                adi
            )
            binding.mediaBanner.setTransitionGenerator(generator)
        }
        val banner =
            if (bannerAnimations) binding.mediaBanner else binding.mediaBannerNoKen
        val viewPager = binding.mediaViewPager
        //tabLayout = binding.mediaTab as AnimatedBottomBar
        viewPager.isUserInputEnabled = false
        viewPager.setPageTransformer(ZoomOutPageTransformer())


        val isDownload = intent.getBooleanExtra("download", false)
        media.selected = model.loadSelected(media, isDownload)

        binding.mediaCoverImage.loadImage(media.cover)
        binding.mediaCoverImage.setOnLongClickListener {
            ImageViewDialog.newInstance(
                this,
                media.userPreferredName + "[Cover]",
                media.cover
            )
        }

        blurImage(banner, media.banner ?: media.cover)
        val gestureDetector = GestureDetector(this, object : GesturesListener() {
            override fun onDoubleClick(event: MotionEvent) {
                if (!(PrefManager.getVal(PrefName.BannerAnimations) as Boolean))
                    snackString(getString(R.string.enable_banner_animations))
                else {
                    binding.mediaBanner.restart()
                    binding.mediaBanner.performClick()
                }
            }

            override fun onLongClick(event: MotionEvent) {
                ImageViewDialog.newInstance(
                    this@MediaDetailsActivity,
                    media.userPreferredName + "[Banner]",
                    media.banner ?: media.cover
                )
                banner.performClick()
            }
        })
        banner.setOnTouchListener { _, motionEvent -> gestureDetector.onTouchEvent(motionEvent);true }
        if (PrefManager.getVal(PrefName.Incognito)) {
            binding.mediaTitle.text = "    ${media.userPreferredName}"
            binding.incognito.visibility = View.VISIBLE
        } else {
            binding.mediaTitle.text = media.userPreferredName
        }
        binding.mediaTitle.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        binding.mediaTitleCollapse.text = media.userPreferredName
        binding.mediaTitleCollapse.setOnLongClickListener {
            copyToClipboard(media.userPreferredName)
            true
        }
        binding.mediaStatus.text = media.status ?: ""

        //Fav Button
        val favButton = if (Anilist.userid != null) {
            if (media.isFav) binding.mediaFav.setImageDrawable(
                AppCompatResources.getDrawable(
                    this,
                    R.drawable.ic_round_favorite_24
                )
            )
            val typedValue = TypedValue()
            this.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSecondary,
                typedValue,
                true
            )
            val color = typedValue.data
            val typedValue2 = TypedValue()
            this.theme.resolveAttribute(
                com.google.android.material.R.attr.colorSecondary,
                typedValue2,
                true
            )
            val color2 = typedValue.data

            PopImageButton(
                scope,
                binding.mediaFav,
                R.drawable.ic_round_favorite_24,
                R.drawable.ic_round_favorite_border_24,
                R.color.bg_opp,
                R.color.violet_400,//TODO: Change to colorSecondary
                media.isFav
            ) {
                media.isFav = it
                Anilist.mutation.toggleFav(media.anime != null, media.id)
                Refresh.all()
            }
        } else {
            binding.mediaFav.visibility = View.GONE
            null
        }

        @SuppressLint("ResourceType")
        fun total() {
            val text = SpannableStringBuilder().apply {
                val typedValue = TypedValue()
                this@MediaDetailsActivity.theme.resolveAttribute(
                    com.google.android.material.R.attr.colorOnBackground,
                    typedValue,
                    true
                )
                val white = typedValue.data
                if (media.userStatus != null) {
                    append(if (media.anime != null) getString(R.string.watched_num) else getString(R.string.read_num))
                    val typedValue = TypedValue()
                    theme.resolveAttribute(
                        com.google.android.material.R.attr.colorSecondary,
                        typedValue,
                        true
                    )
                    bold { color(typedValue.data) { append("${media.userProgress}") } }
                    append(
                        if (media.anime != null) getString(R.string.episodes_out_of) else getString(
                            R.string.chapters_out_of
                        )
                    )
                } else {
                    append(
                        if (media.anime != null) getString(R.string.episodes_total_of) else getString(
                            R.string.chapters_total_of
                        )
                    )
                }
                if (media.anime != null) {
                    if (media.anime!!.nextAiringEpisode != null) {
                        bold { color(white) { append("${media.anime!!.nextAiringEpisode}") } }
                        append(" / ")
                    }
                    bold { color(white) { append("${media.anime!!.totalEpisodes ?: "??"}") } }
                } else
                    bold { color(white) { append("${media.manga!!.totalChapters ?: "??"}") } }
            }
            binding.mediaTotal.text = text
        }

        fun progress() {
            val statuses: Array<String> = resources.getStringArray(R.array.status)
            val statusStrings =
                if (media.manga == null) resources.getStringArray(R.array.status_anime) else resources.getStringArray(
                    R.array.status_manga
                )
            val userStatus =
                if (media.userStatus != null) statusStrings[statuses.indexOf(media.userStatus)] else statusStrings[0]

            if (media.userStatus != null) {
                binding.mediaTotal.visibility = View.VISIBLE
                binding.mediaAddToList.text = userStatus
            } else {
                binding.mediaAddToList.setText(R.string.add)
            }
            total()
            binding.mediaAddToList.setOnClickListener {
                if (Anilist.userid != null) {
                    if (supportFragmentManager.findFragmentByTag("dialog") == null)
                        MediaListDialogFragment().show(supportFragmentManager, "dialog")
                } else snackString(getString(R.string.please_login_anilist))
            }
            binding.mediaAddToList.setOnLongClickListener {
                PrefManager.setCustomVal(
                    "${media.id}_progressDialog",
                    true,
                )
                snackString(getString(R.string.auto_update_reset))
                true
            }
        }
        progress()

        model.getMedia().observe(this) {
            if (it != null) {
                media = it
                scope.launch {
                    if (media.isFav != favButton?.clicked) favButton?.clicked()
                }

                binding.mediaNotify.setOnClickListener {
                    val i = Intent(Intent.ACTION_SEND)
                    i.type = "text/plain"
                    i.putExtra(Intent.EXTRA_TEXT, media.shareLink)
                    startActivity(Intent.createChooser(i, media.userPreferredName))
                }
                binding.mediaNotify.setOnLongClickListener {
                    openLinkInBrowser(media.shareLink)
                    true
                }
                binding.mediaCover.setOnClickListener {
                    openLinkInBrowser(media.shareLink)
                }
                progress()
            }
        }
        tabLayout = TripleNavAdapter(
            binding.mediaTab1,
            binding.mediaTab2,
            binding.mediaTab3,
            media.anime != null,
            media.format ?: "",
            isVertical == 1
        )
        adult = media.isAdult
        if (media.anime != null) {
            viewPager.adapter =
                ViewPagerAdapter(supportFragmentManager, lifecycle, SupportedMedia.ANIME, media, intent.getIntExtra("commentId", -1))
        } else if (media.manga != null) {
            viewPager.adapter = ViewPagerAdapter(
                supportFragmentManager,
                lifecycle,
                if (media.format == "NOVEL") SupportedMedia.NOVEL else SupportedMedia.MANGA,
                media,
                intent.getIntExtra("commentId", -1)
            )
            anime = false
        }


        selected = media.selected!!.window
        binding.mediaTitle.translationX = -screenWidth

        tabLayout.selectionListener = { selected, newId ->
            binding.commentInputLayout.visibility = if (selected == 2) View.VISIBLE else View.GONE
            this.selected = selected
            selectFromID(newId)
            viewPager.setCurrentItem(selected, false)
            val sel = model.loadSelected(media, isDownload)
            sel.window = selected
            model.saveSelected(media.id, sel)
        }
        tabLayout.selectTab(selected)
        selectFromID(tabLayout.selected)
        viewPager.setCurrentItem(selected, false)

        if (model.continueMedia == null && media.cameFromContinue) {
            model.continueMedia = PrefManager.getVal(PrefName.ContinueMedia)
            selected = 1
        }
        val frag = intent.getStringExtra("FRAGMENT_TO_LOAD")
        if (frag != null) {
            selected = 2
        }

        val live = Refresh.activity.getOrPut(this.hashCode()) { MutableLiveData(true) }
        live.observe(this) {
            if (it) {
                scope.launch(Dispatchers.IO) {
                    model.loadMedia(media)
                    live.postValue(false)
                }
            }
        }
    }

    private fun selectFromID(id: Int) {
        when (id) {
            R.id.info -> {
                selected = 0
            }

            R.id.watch, R.id.read -> {
                selected = 1
            }

            R.id.comment -> {
                selected = 2
            }
        }
    }

    private fun idFromSelect(): Int {
        if (anime) when (selected) {
            0 -> return R.id.info
            1 -> return R.id.watch
            2 -> return R.id.comment
        }
        else when (selected) {
            0 -> return R.id.info
            1 -> return R.id.read
            2 -> return R.id.comment
        }
        return R.id.info
    }

    override fun onResume() {
        if (this::tabLayout.isInitialized) {
            tabLayout.selectTab(selected)
        }
        super.onResume()
    }

    private enum class SupportedMedia {
        ANIME, MANGA, NOVEL
    }

    //ViewPager
    private class ViewPagerAdapter(
        fragmentManager: FragmentManager,
        lifecycle: Lifecycle,
        private val mediaType: SupportedMedia,
        private val media: Media,
        private val commentId: Int
    ) :
        FragmentStateAdapter(fragmentManager, lifecycle) {

        override fun getItemCount(): Int = 3

        override fun createFragment(position: Int): Fragment = when (position) {
            0 -> MediaInfoFragment()
            1 -> when (mediaType) {
                SupportedMedia.ANIME -> AnimeWatchFragment()
                SupportedMedia.MANGA -> MangaReadFragment()
                SupportedMedia.NOVEL -> NovelReadFragment()
            }
            2 -> {
                val fragment = CommentsFragment()
                val bundle = Bundle()
                bundle.putInt("mediaId", media.id)
                bundle.putString("mediaName", media.mainName())
                if (commentId != -1) bundle.putInt("commentId", commentId)
                fragment.arguments = bundle
                fragment
            }

            else -> MediaInfoFragment()
        }
    }

    //Collapsing UI Stuff
    private var isCollapsed = false
    private val percent = 45
    private var mMaxScrollSize = 0
    private var screenWidth: Float = 0f

    override fun onOffsetChanged(appBar: AppBarLayout, i: Int) {
        if (mMaxScrollSize == 0) mMaxScrollSize = appBar.totalScrollRange
        val percentage = abs(i) * 100 / mMaxScrollSize

        binding.mediaCover.visibility =
            if (binding.mediaCover.scaleX == 0f) View.GONE else View.VISIBLE
        val duration = (200 * (PrefManager.getVal(PrefName.AnimationSpeed) as Float)).toLong()
        val typedValue = TypedValue()
        this@MediaDetailsActivity.theme.resolveAttribute(
            com.google.android.material.R.attr.colorSecondary,
            typedValue,
            true
        )
        val color = typedValue.data
        if (percentage >= percent && !isCollapsed) {
            isCollapsed = true
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", 0f).setDuration(duration)
                .start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", screenWidth)
                .setDuration(duration).start()
            binding.mediaBanner.pause()
        }
        if (percentage <= percent && isCollapsed) {
            isCollapsed = false
            ObjectAnimator.ofFloat(binding.mediaTitle, "translationX", -screenWidth)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaAccessContainer, "translationX", 0f)
                .setDuration(duration).start()
            ObjectAnimator.ofFloat(binding.mediaCover, "translationX", 0f).setDuration(duration)
                .start()
            ObjectAnimator.ofFloat(binding.mediaCollapseContainer, "translationX", 0f)
                .setDuration(duration).start()
            if (PrefManager.getVal(PrefName.BannerAnimations)) binding.mediaBanner.resume()
        }
        if (percentage == 1 && model.scrolledToTop.value != false) model.scrolledToTop.postValue(
            false
        )
        if (percentage == 0 && model.scrolledToTop.value != true) model.scrolledToTop.postValue(true)
    }

    class PopImageButton(
        private val scope: CoroutineScope,
        private val image: ImageView,
        private val d1: Int,
        private val d2: Int,
        private val c1: Int,
        private val c2: Int,
        var clicked: Boolean,
        needsInitialClick: Boolean = false,
        callback: suspend (Boolean) -> (Unit)
    ) {
        private var disabled = false
        private val context = image.context
        private var pressable = true

        init {
            enabled(true)
            if (needsInitialClick) {
                scope.launch {
                    clicked()
                }
            }
            image.setOnClickListener {
                if (pressable && !disabled) {
                    pressable = false
                    clicked = !clicked
                    scope.launch {
                        launch(Dispatchers.IO) {
                            callback.invoke(clicked)
                        }
                        clicked()
                        pressable = true
                    }
                }
            }
        }

        suspend fun clicked() {
            ObjectAnimator.ofFloat(image, "scaleX", 1f, 0f).setDuration(69).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1f, 0f).setDuration(100).start()
            delay(100)

            if (clicked) {
                ObjectAnimator.ofArgb(
                    image,
                    "ColorFilter",
                    ContextCompat.getColor(context, c1),
                    ContextCompat.getColor(context, c2)
                ).setDuration(120).start()
                image.setImageDrawable(AppCompatResources.getDrawable(context, d1))
            } else image.setImageDrawable(AppCompatResources.getDrawable(context, d2))
            ObjectAnimator.ofFloat(image, "scaleX", 0f, 1.5f).setDuration(120).start()
            ObjectAnimator.ofFloat(image, "scaleY", 0f, 1.5f).setDuration(100).start()
            delay(120)
            ObjectAnimator.ofFloat(image, "scaleX", 1.5f, 1f).setDuration(100).start()
            ObjectAnimator.ofFloat(image, "scaleY", 1.5f, 1f).setDuration(100).start()
            delay(200)
            if (clicked) {
                ObjectAnimator.ofArgb(
                    image,
                    "ColorFilter",
                    ContextCompat.getColor(context, c2),
                    ContextCompat.getColor(context, c1)
                ).setDuration(200).start()
            }
        }

        fun enabled(enabled: Boolean) {
            disabled = !enabled
            image.alpha = if (disabled) 0.33f else 1f
        }
    }

    companion object {
        var mediaSingleton: Media? = null
    }
}