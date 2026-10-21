package com.openlauncher.tv.ui.home

import android.app.Dialog
import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.content.ContextCompat
import com.openlauncher.tv.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 壁纸设置面板（电视遥控器操作）：
 * 图片种类 → 图片来源 → 接口地址 → 预览，确认某张预览图就立刻换上。
 *
 * 「种类」和「来源」是联动的一组：点种类会跳到它对应的默认来源，点来源会把种类高亮切过去，
 * 保证两个列表永远指向同一件事，不会出现"选了动漫但来源还是 Bing"的欺骗。
 */
class WallpaperSettingsDialog(
    context: Context,
    private val repo: WallpaperRepository,
    private val onChanged: (WallpaperRepository.Loaded) -> Unit
) : Dialog(context, R.style.DialogSheet) {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var thumbJob: Job? = null

    private lateinit var rowKind: LinearLayout
    private lateinit var rowSourceA: LinearLayout
    private lateinit var rowSourceB: LinearLayout
    private lateinit var rowThumbs: LinearLayout
    private lateinit var tvEndpoint: TextView

    private val kindChips = LinkedHashMap<WallpaperRepository.Kind, View>()
    private val sourceChips = LinkedHashMap<WallpaperRepository.Source, View>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.dialog_wallpaper)
        window?.apply {
            setLayout(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
            setBackgroundDrawableResource(android.R.color.transparent)
            addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
        }

        rowKind = findViewById(R.id.row_kind)
        rowSourceA = findViewById(R.id.row_source_a)
        rowSourceB = findViewById(R.id.row_source_b)
        rowThumbs = findViewById(R.id.row_thumbs)
        tvEndpoint = findViewById(R.id.tv_endpoint)

        findViewById<View>(R.id.scrim).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_sheet_close).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_done).setOnClickListener { dismiss() }
        findViewById<View>(R.id.btn_shuffle).setOnClickListener { shuffleNow() }
        findViewById<View>(R.id.btn_edit_url).setOnClickListener { editCustomUrl() }

        buildKindRow()
        buildSourceRow()
        refreshSelection()
        wireFocus()
        loadThumbs()
    }

    /**
     * 手动接上/下焦点链路。
     * 电视上默认的焦点搜索经常在两个横向 LinearLayout 之间"看不见"对方（尤其是动态添加的 View），
     * 与其指望几何算法，不如把每一行的邻居显式写死，行为可预测。
     */
    private fun wireFocus() {
        val kinds = rowKind.children().toList()
        val sourceA = rowSourceA.children().toList()
        val sourceB = rowSourceB.children().toList()
        linkDown(kinds, sourceA)
        linkUp(sourceA, kinds)
        linkDown(sourceA, sourceB)
        linkUp(sourceB, sourceA)
        val editUrl = findViewById<View>(R.id.btn_edit_url)
        sourceB.forEach { it.nextFocusDownId = editUrl.id }
        editUrl.nextFocusDownId = R.id.btn_shuffle
        findViewById<View>(R.id.btn_shuffle).apply {
            nextFocusUpId = R.id.btn_edit_url
            nextFocusRightId = R.id.btn_done
        }
        findViewById<View>(R.id.btn_done).apply {
            nextFocusUpId = R.id.btn_edit_url
            nextFocusLeftId = R.id.btn_shuffle
        }
        findViewById<View>(R.id.btn_sheet_close).apply {
            nextFocusDownId = kinds.firstOrNull()?.id ?: View.NO_ID
        }
    }

    private fun linkDown(from: List<View>, to: List<View>) {
        if (to.isEmpty()) return
        from.forEachIndexed { index, view ->
            view.nextFocusDownId = to[index.coerceAtMost(to.size - 1)].id
        }
    }

    private fun linkUp(from: List<View>, to: List<View>) {
        if (to.isEmpty()) return
        from.forEachIndexed { index, view ->
            view.nextFocusUpId = to[index.coerceAtMost(to.size - 1)].id
        }
    }

    private fun ViewGroup.children(): Sequence<View> =
        (0 until childCount).asSequence().map { getChildAt(it) }

    override fun onStart() {
        super.onStart()
        // 等一帧再要焦点：卡片刚 inflate 出来时还没测量完，直接 requestFocus 会被丢掉
        rowKind.post { rowKind.getChildAt(0)?.requestFocus() }
    }

    override fun onStop() {
        thumbJob?.cancel()
        super.onStop()
    }

    override fun dismiss() {
        scope.cancel()
        super.dismiss()
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) {
            dismiss()
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    // ---- 选项 -------------------------------------------------------------------

    private fun buildKindRow() {
        rowKind.removeAllViews()
        kindChips.clear()
        WallpaperRepository.Kind.entries.forEach { kind ->
            val icon = when (kind) {
                WallpaperRepository.Kind.DAILY -> R.drawable.ic_kind_sun
                WallpaperRepository.Kind.WORLD -> R.drawable.ic_kind_globe
                WallpaperRepository.Kind.PHOTO -> R.drawable.ic_kind_camera
                WallpaperRepository.Kind.ANIME -> R.drawable.ic_kind_star
                WallpaperRepository.Kind.SOLID -> R.drawable.ic_kind_circle
            }
            val chip = createChip(icon, kind.label)
            chip.setOnClickListener {
                repo.setSource(kind.defaultSource)
                refreshSelection()
                loadThumbs()
            }
            rowKind.addView(chip)
            kindChips[kind] = chip
        }
    }

    private fun buildSourceRow() {
        rowSourceA.removeAllViews()
        rowSourceB.removeAllViews()
        sourceChips.clear()

        val rowA = listOf(
            WallpaperRepository.Source.BING_CN,
            WallpaperRepository.Source.BING_JP,
            WallpaperRepository.Source.BING_US,
            WallpaperRepository.Source.BING_GB
        )
        val rowB = listOf(
            WallpaperRepository.Source.SAFEBOORU,
            WallpaperRepository.Source.SOLID,
            WallpaperRepository.Source.CUSTOM
        )
        rowA.forEach { source ->
            val icon = if (source == WallpaperRepository.Source.BING_CN) R.drawable.ic_kind_globe else 0
            val chip = createChip(icon, source.label)
            chip.setOnClickListener {
                repo.setSource(source)
                refreshSelection()
                loadThumbs()
            }
            rowSourceA.addView(chip)
            sourceChips[source] = chip
        }
        rowB.forEach { source ->
            val chip = createChip(0, shortLabel(source))
            chip.setOnClickListener { onSourceClicked(source) }
            rowSourceB.addView(chip)
            sourceChips[source] = chip
        }
    }

    private fun shortLabel(source: WallpaperRepository.Source): String = when (source) {
        WallpaperRepository.Source.SAFEBOORU -> "safebooru 动漫"
        WallpaperRepository.Source.SOLID -> "纯色"
        WallpaperRepository.Source.CUSTOM -> "自定义"
        else -> source.label
    }

    private fun onSourceClicked(source: WallpaperRepository.Source) {
        if (source == WallpaperRepository.Source.CUSTOM && repo.customUrl().isBlank()) {
            // 还没填地址就点「自定义」：直接把编辑框弹出来，别让用户猜
            editCustomUrl()
            return
        }
        repo.setSource(source)
        refreshSelection()
        loadThumbs()
    }

    private fun refreshSelection() {
        val source = repo.source()
        val kind = WallpaperRepository.Kind.of(source)
        kindChips.forEach { (key, view) -> view.isSelected = key == kind }
        sourceChips.forEach { (key, view) -> view.isSelected = key == source }
        tvEndpoint.text = repo.endpoint()
        // 编辑按钮排在最后一行，往下走一步就是「立即换一张」
        findViewById<View>(R.id.btn_edit_url).nextFocusDownId = R.id.btn_shuffle
    }

    // ---- 预览 -------------------------------------------------------------------

    private fun loadThumbs() {
        thumbJob?.cancel()
        rowThumbs.removeAllViews()
        val source = repo.source()

        if (source == WallpaperRepository.Source.SOLID) {
            rowThumbs.addView(buildSolidThumb())
            return
        }

        val hint = TextView(context).apply {
            text = "正在取预览图…"
            setTextColor(0x800F2E26.toInt())
            textSize = 11f
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(58)
            )
        }
        rowThumbs.addView(hint)

        thumbJob = scope.launch {
            val candidates = repo.candidates(THUMB_COUNT)
            if (candidates.isEmpty()) {
                hint.text = "这个图源暂时没取到图，检查网络后再试"
                return@launch
            }
            rowThumbs.removeAllViews()
            val currentUrl = repo.meta()?.url
            candidates.forEach { candidate ->
                val holder = buildThumb()
                holder.root.isSelected = candidate.url == currentUrl
                rowThumbs.addView(holder.root)
                launch {
                    val bitmap = repo.loadThumb(candidate)
                    if (bitmap != null) {
                        holder.image.setImageBitmap(bitmap)
                    } else {
                        holder.image.setImageResource(R.drawable.ic_kind_globe)
                        holder.image.setColorFilter(0x330F2E26)
                    }
                }
                // 预览图的上一格是「编辑」按钮，视觉上正好在它下面
                holder.root.nextFocusUpId = R.id.btn_edit_url
                holder.root.nextFocusDownId = R.id.btn_shuffle
                holder.root.setOnClickListener { applyCandidate(candidate, holder) }
            }
            // 预览图夹在「接口地址 / 编辑」和下面两个按钮中间，把上下都接上
            val firstThumb = rowThumbs.getChildAt(0)?.id ?: View.NO_ID
            val editUrl = findViewById<View>(R.id.btn_edit_url)
            val sourceBChips = rowSourceB.children().toList()
            editUrl.nextFocusDownId = firstThumb
            editUrl.nextFocusUpId = sourceBChips.lastOrNull()?.id ?: View.NO_ID
            findViewById<View>(R.id.btn_shuffle).nextFocusUpId = firstThumb
            findViewById<View>(R.id.btn_done).nextFocusUpId = firstThumb
            if (firstThumb != View.NO_ID) {
                // 来源第二行最后一个（自定义）按「下」进「编辑」，其余直接进预览
                sourceBChips.dropLast(1).forEach { it.nextFocusDownId = firstThumb }
                sourceBChips.lastOrNull()?.nextFocusDownId = editUrl.id
            }
        }
    }

    private fun applyCandidate(
        candidate: WallpaperRepository.Candidate,
        holder: ThumbHolder
    ) {
        toast("正在换成这张…")
        scope.launch {
            val loaded = repo.applyCandidate(candidate)
            if (loaded == null) {
                toast("这张没下载成功，换一张试试")
                return@launch
            }
            onChanged(loaded)
            dismiss()
        }
    }

    private fun shuffleNow() {
        toast("正在换一张壁纸…")
        scope.launch {
            val loaded = if (repo.source() == WallpaperRepository.Source.SOLID) {
                repo.applySolid()
            } else {
                repo.refreshNow()
            }
            if (loaded == null) {
                toast("暂时连不上图源，先看当前这张")
                return@launch
            }
            onChanged(loaded)
            dismiss()
        }
    }

    // ---- 自定义地址 --------------------------------------------------------------

    private fun editCustomUrl() {
        val input = EditText(context).apply {
            setText(repo.customUrl())
            hint = "https://… 直接填图片地址"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            setSingleLine()
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0x80FFFFFF.toInt())
            setPadding(dp(16), dp(12), dp(16), dp(12))
        }
        val wrapper = FrameLayout(context).apply {
            setPadding(dp(20), dp(8), dp(20), 0)
            addView(input, FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        AlertDialog.Builder(context, R.style.DialogDark)
            .setTitle("自定义图片地址")
            .setView(wrapper)
            .setPositiveButton("使用") { _, _ ->
                val url = input.text.toString().trim()
                if (url.isBlank()) {
                    toast("地址是空的，保持原样")
                    return@setPositiveButton
                }
                repo.setCustomUrl(url)
                repo.setSource(WallpaperRepository.Source.CUSTOM)
                refreshSelection()
                loadThumbs()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---- 控件工厂 ---------------------------------------------------------------

    private fun createChip(iconRes: Int, label: String): LinearLayout {
        val chip = LinearLayout(context).apply {
            id = View.generateViewId()
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = AppCompatResources.getDrawable(context, R.drawable.bg_chip)
            isFocusable = true
            isFocusableInTouchMode = true
            setPadding(dp(14), 0, dp(16), 0)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(36)
            ).apply { marginEnd = dp(8) }
        }
        val tint = AppCompatResources.getColorStateList(context, R.color.chip_text)
        if (iconRes != 0) {
            val icon = ImageView(context).apply {
                setImageResource(iconRes)
                imageTintList = tint
                isDuplicateParentStateEnabled = true
            }
            chip.addView(icon, LinearLayout.LayoutParams(dp(16), dp(16)).apply {
                marginEnd = dp(6)
            })
        }
        val text = TextView(context).apply {
            this.text = label
            textSize = 12f
            setTextColor(tint)
            isDuplicateParentStateEnabled = true
            includeFontPadding = false
            typeface = android.graphics.Typeface.create("sans-serif-medium", 0)
        }
        chip.addView(text, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))
        return chip
    }

    private class ThumbHolder(val root: FrameLayout, val image: ImageView)

    private fun buildThumb(): ThumbHolder {
        val root = FrameLayout(context).apply {
            id = View.generateViewId()
            background = AppCompatResources.getDrawable(context, R.drawable.bg_thumb)
            isFocusable = true
            isFocusableInTouchMode = true
            isClickable = true
            setPadding(dp(3), dp(3), dp(3), dp(3))
            layoutParams = LinearLayout.LayoutParams(dp(100), dp(58)).apply { marginEnd = dp(8) }
        }
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            setBackgroundColor(0x140F2E26)
        }
        root.addView(image, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ))
        return ThumbHolder(root, image)
    }

    /** 纯色源不联网，直接画一块渐变当预览。 */
    private fun buildSolidThumb(): View {
        val holder = buildThumb()
        holder.image.background = GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            intArrayOf(0xFF0E6B52.toInt(), 0xFF12B57F.toInt(), 0xFF8FE3C6.toInt())
        )
        holder.root.setOnClickListener {
            scope.launch {
                val loaded = repo.applySolid()
                if (loaded != null) {
                    onChanged(loaded)
                    dismiss()
                }
            }
        }
        return holder.root
    }

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(message: String) {
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val THUMB_COUNT = 4
    }
}
