package com.filtermaster.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    // ---------- 数据 ----------
    private lateinit var items: MutableList<FilterItem>
    private val displayed = mutableListOf<FilterItem>()
    private lateinit var adapter: FilterAdapter

    private var currentType = "all"
    private var keyword = ""

    // ---------- 视图 ----------
    private lateinit var etSearch: EditText
    private lateinit var btnClear: ImageButton
    private lateinit var filterBar: LinearLayout
    private lateinit var tvStats: TextView
    private lateinit var tvTotalCount: TextView
    private lateinit var recycler: RecyclerView
    private lateinit var emptyView: View
    private lateinit var emptyIcon: TextView
    private lateinit var emptyTitle: TextView
    private lateinit var emptySub: TextView

    // 编辑弹层视图（懒加载）
    private var editDialog: BottomSheetDialog? = null
    private lateinit var editTitle: TextView
    private lateinit var chipGroupType: ChipGroup
    private lateinit var etGoodsCode: EditText
    private lateinit var etOeCode: EditText
    private lateinit var etCarModel: EditText
    private lateinit var etSpec: EditText
    private lateinit var etRing: EditText
    private lateinit var etBox: EditText
    private lateinit var etNotes: EditText
    private lateinit var imgPreviewWrap: View
    private lateinit var ivPreview: ImageView
    private var editingId: Long? = null
    private var pickedType: String = ""
    private var currentImagePath: String? = null

    // 详情弹层
    private var detailDialog: BottomSheetDialog? = null
    private var currentDetailId: Long? = null

    private var scanMode = "edit"   // edit: 填OE码 | search: 填搜索框
    private var pendingCameraFile: File? = null
    private var pendingExportText: String? = null

    private val TYPE_LIST = listOf("机油滤芯", "空气滤芯", "空调滤芯", "燃油滤芯")
    private val FILTER_TAGS = listOf(
        "all" to "全部",
        "机油滤芯" to "🛢️ 机油",
        "空气滤芯" to "🌬️ 空气",
        "空调滤芯" to "❄️ 空调",
        "燃油滤芯" to "⛽ 燃油"
    )
    private val tagViews = mutableMapOf<String, TextView>()

    // ---------- ActivityResult ----------
    private val scanLauncher = registerForActivityResult(ScanContract()) { result ->
        val content = result.contents ?: return@registerForActivityResult
        if (scanMode == "search") {
            etSearch.setText(content)
            keyword = content
            renderList()
            toast("已按扫码结果搜索")
        } else {
            if (::etOeCode.isInitialized) etOeCode.setText(content)
            toast("扫码成功 ✓")
        }
    }

    private val cameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val temp = pendingCameraFile
            if (ok && temp != null) {
                processCapturedImage(temp)
                temp.delete()
            }
        }

    private val albumLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { importImageFromUri(it) }
        }

    private val exportLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
            val text = pendingExportText
            if (uri != null && text != null) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    }
                }.onSuccess {
                    toast("导出成功 ✓")
                }.onFailure {
                    toast("导出失败：${it.message}")
                }
            }
            pendingExportText = null
        }

    private val importLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { importCsvFromUri(it) }
        }

    // 相机运行时权限：声明了 CAMERA 权限后，未授权直接调起系统相机会闪退
    private var pendingCameraAction: (() -> Unit)? = null
    private val cameraPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val action = pendingCameraAction
            pendingCameraAction = null
            if (granted) {
                action?.invoke()
            } else {
                toast("需要相机权限才能使用该功能")
            }
        }

    // ---------- 生命周期 ----------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        items = FilterStore.load(this)

        bindViews()
        setupFilterBar()
        setupSearch()
        setupButtons()

        adapter = FilterAdapter(displayed) { item -> openDetail(item) }
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter

        renderList()
    }

    override fun onPause() {
        super.onPause()
        FilterStore.save(this, items)
    }

    private fun bindViews() {
        etSearch = findViewById(R.id.etSearch)
        btnClear = findViewById(R.id.btnClear)
        filterBar = findViewById(R.id.filterBar)
        tvStats = findViewById(R.id.tvStats)
        tvTotalCount = findViewById(R.id.tvTotalCount)
        recycler = findViewById(R.id.recycler)
        emptyView = findViewById(R.id.emptyView)
        emptyIcon = findViewById(R.id.emptyIcon)
        emptyTitle = findViewById(R.id.emptyTitle)
        emptySub = findViewById(R.id.emptySub)
    }

    // ---------- 类型筛选条 ----------
    private fun setupFilterBar() {
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { marginEnd = dp(8) }

        FILTER_TAGS.forEach { (type, label) ->
            val tv = TextView(this)
            tv.text = label
            tv.textSize = 13f
            tv.setPadding(dp(15), dp(7), dp(15), dp(7))
            tv.layoutParams = params
            tv.setBackgroundResource(R.drawable.bg_pill)
            tv.setOnClickListener {
                selectType(type)
                renderList()
            }
            filterBar.addView(tv)
            tagViews[type] = tv
        }
        selectType("all")
    }

    private fun selectType(type: String) {
        currentType = type
        tagViews.forEach { (t, v) ->
            if (t == type) {
                // 实心深蓝底 + 白字，高对比
                v.background?.mutate()?.setTint(ContextCompat.getColor(this, R.color.primary_deep))
                v.setTextColor(Color.WHITE)
                v.setTypeface(v.typeface, android.graphics.Typeface.BOLD)
            } else {
                v.background?.mutate()?.setTint(Color.parseColor("#29FFFFFF"))
                v.setTextColor(Color.parseColor("#E6FFFFFF"))
                v.setTypeface(v.typeface, android.graphics.Typeface.NORMAL)
            }
        }
    }

    // ---------- 搜索 ----------
    private fun setupSearch() {
        etSearch.addTextChangedListener(object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                keyword = s?.toString()?.trim().orEmpty()
                btnClear.visibility = if (keyword.isEmpty()) View.GONE else View.VISIBLE
                renderList()
            }
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
        })
    }

    private fun setupButtons() {
        btnClear.setOnClickListener {
            etSearch.setText("")
        }
        findViewById<ImageButton>(R.id.btnScanSearch).setOnClickListener { startScan("search") }
        findViewById<View>(R.id.fabScan).setOnClickListener { startScan("search") }
        findViewById<View>(R.id.fabAdd).setOnClickListener { openEditor(null) }
        findViewById<TextView>(R.id.btnImport).setOnClickListener { startImport() }
        findViewById<TextView>(R.id.btnExport).setOnClickListener { startExport() }
    }

    // ---------- 列表渲染 ----------
    private fun renderList() {
        displayed.clear()
        displayed.addAll(items.asSequence()
            .filter { currentType == "all" || it.type == currentType }
            .filter { keyword.isEmpty() || matchesKeyword(it, keyword.lowercase()) }
            .toList())

        adapter.submit(displayed)

        val html = getString(R.string.stats_fmt, displayed.size.toString())
        tvStats.text = html
        tvTotalCount.text = getString(R.string.count_pill_fmt, items.size.toString())

        val searching = keyword.isNotEmpty() || currentType != "all"
        if (displayed.isEmpty()) {
            recycler.visibility = View.GONE
            emptyView.visibility = View.VISIBLE
            if (searching) {
                emptyIcon.text = "🔍"
                emptyTitle.text = "没有找到匹配的记录"
                emptySub.text = "换个关键词试试"
            } else {
                emptyIcon.text = "📦"
                emptyTitle.text = "暂无滤芯数据"
                emptySub.text = "点击右下角 ＋ 添加第一条记录"
            }
        } else {
            recycler.visibility = View.VISIBLE
            emptyView.visibility = View.GONE
        }
    }

    // ---------- 编辑弹层 ----------
    private fun ensureEditDialog(): BottomSheetDialog {
        editDialog?.let { return it }
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_edit, null)
        dialog.setContentView(view)

        editTitle = view.findViewById(R.id.sheetTitle)
        chipGroupType = view.findViewById(R.id.chipGroupType)
        etGoodsCode = view.findViewById(R.id.etGoodsCode)
        etOeCode = view.findViewById(R.id.etOeCode)
        etCarModel = view.findViewById(R.id.etCarModel)
        etSpec = view.findViewById(R.id.etSpec)
        etRing = view.findViewById(R.id.etRing)
        etBox = view.findViewById(R.id.etBox)
        etNotes = view.findViewById(R.id.etNotes)
        imgPreviewWrap = view.findViewById(R.id.imgPreviewWrap)
        ivPreview = view.findViewById(R.id.ivPreview)

        buildTypeChips()

        view.findViewById<ImageButton>(R.id.btnSheetClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnCancel).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnSave).setOnClickListener { saveFromEditor() }
        view.findViewById<View>(R.id.btnScanOe).setOnClickListener { startScan("edit") }
        view.findViewById<View>(R.id.btnTakePhoto).setOnClickListener { takePhoto() }
        view.findViewById<View>(R.id.btnPickPhoto).setOnClickListener {
            albumLauncher.launch("image/*")
        }
        view.findViewById<View>(R.id.btnRemoveImage).setOnClickListener {
            currentImagePath?.let { p -> File(p).delete() }
            currentImagePath = null
            imgPreviewWrap.visibility = View.GONE
        }

        editDialog = dialog
        return dialog
    }

    private fun buildTypeChips() {
        chipGroupType.removeAllViews()
        chipGroupType.isSingleSelection = true
        TYPE_LIST.forEach { type ->
            val chip = Chip(this)
            chip.text = "${FilterAdapter.typeIcon(type)} ${type.removeSuffix("滤芯")}"
            chip.isCheckable = true
            chip.tag = type
            chip.chipStrokeWidth = 0f
            chip.isCheckedIconVisible = false
            // 选中：实心蓝底白字，未选中：灰底深字（高对比度）
            chip.chipBackgroundColor = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf()
                ),
                intArrayOf(Color.parseColor("#2F6BFF"), Color.parseColor("#F4F6FB"))
            )
            chip.setTextColor(
                ColorStateList(
                    arrayOf(
                        intArrayOf(android.R.attr.state_checked),
                        intArrayOf()
                    ),
                    intArrayOf(Color.WHITE, Color.parseColor("#55627A"))
                )
            )
            chip.textSize = 14f
            chip.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) pickedType = chip.tag as String
            }
            chipGroupType.addView(chip)
        }
    }

    private fun setTypeChipChecked(type: String) {
        pickedType = type
        (0 until chipGroupType.childCount).forEach { i ->
            val c = chipGroupType.getChildAt(i) as Chip
            c.isChecked = (c.tag == type && type.isNotEmpty())
        }
    }

    private fun openEditor(item: FilterItem?) {
        val dialog = ensureEditDialog()
        // id==0 表示来自车型库的预填模板，仍按新增处理
        editingId = item?.id?.takeIf { it != 0L }
        editTitle.text = if (item == null) "新增滤芯" else "编辑滤芯"
        etGoodsCode.setText(item?.goodsCode.orEmpty())
        etOeCode.setText(item?.oeCode.orEmpty())
        etCarModel.setText(item?.carModel.orEmpty())
        etSpec.setText(item?.specification.orEmpty())
        etRing.setText(item?.rubberRing.orEmpty())
        etBox.setText(item?.boxInfo.orEmpty())
        etNotes.setText(item?.notes.orEmpty())
        currentImagePath = item?.imagePath
        setTypeChipChecked(item?.type.orEmpty())
        refreshPreview()
        dialog.show()
    }

    private fun refreshPreview() {
        val path = currentImagePath
        if (!path.isNullOrBlank() && File(path).exists()) {
            ivPreview.setImageBitmap(FilterAdapter.decodeSampled(path, 600))
            imgPreviewWrap.visibility = View.VISIBLE
        } else {
            imgPreviewWrap.visibility = View.GONE
        }
    }

    private fun saveFromEditor() {
        val goods = etGoodsCode.text.toString().trim()
        val oe = etOeCode.text.toString().trim()
        val car = etCarModel.text.toString().trim()
        if (goods.isEmpty() && oe.isEmpty() && car.isEmpty()) {
            toast("请至少填写 编码 / OE码 / 车型")
            return
        }
        val eid = editingId
        if (eid != null) {
            val idx = items.indexOfFirst { it.id == eid }
            if (idx >= 0) {
                val old = items[idx]
                items[idx] = old.copy(
                    type = pickedType,
                    goodsCode = goods, oeCode = oe, carModel = car,
                    specification = etSpec.text.toString().trim(),
                    rubberRing = etRing.text.toString().trim(),
                    boxInfo = etBox.text.toString().trim(),
                    notes = etNotes.text.toString().trim(),
                    imagePath = currentImagePath
                )
            }
        } else {
            items.add(0, FilterItem(
                id = System.currentTimeMillis(),
                type = pickedType,
                goodsCode = goods, oeCode = oe, carModel = car,
                specification = etSpec.text.toString().trim(),
                rubberRing = etRing.text.toString().trim(),
                boxInfo = etBox.text.toString().trim(),
                notes = etNotes.text.toString().trim(),
                imagePath = currentImagePath,
                createdAt = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US
                ).format(java.util.Date())
            ))
        }
        FilterStore.save(this, items)
        editDialog?.dismiss()
        renderList()
        toast("已保存 ✓")
    }

    // ---------- 详情弹层 ----------
    private fun openDetail(item: FilterItem) {
        currentDetailId = item.id
        if (detailDialog == null) {
            val dialog = BottomSheetDialog(this)
            val view = layoutInflater.inflate(R.layout.sheet_detail, null)
            dialog.setContentView(view)
            view.findViewById<ImageButton>(R.id.btnDetailClose).setOnClickListener { dialog.dismiss() }
            view.findViewById<View>(R.id.btnDelete).setOnClickListener { confirmDelete() }
            view.findViewById<View>(R.id.btnCopyOe).setOnClickListener { copyCurrentOe() }
            view.findViewById<View>(R.id.btnEdit).setOnClickListener {
                val cur = items.find { it.id == currentDetailId }
                detailDialog?.dismiss()
                cur?.let { openEditor(it) }
            }
            detailDialog = dialog
        }
        val container = detailDialog!!.findViewById<LinearLayout>(R.id.detailContainer)!!
        container.removeAllViews()
        buildDetailContent(container, item)
        detailDialog!!.show()
    }

    private fun buildDetailContent(container: LinearLayout, item: FilterItem) {
        // 图片
        item.imagePath?.takeIf { File(it).exists() }?.let { path ->
            val ctx = this
            val iv = ImageView(ctx)
            iv.adjustViewBounds = true
            iv.scaleType = ImageView.ScaleType.FIT_CENTER
            iv.setImageBitmap(FilterAdapter.decodeSampled(path, 900))
            iv.setPadding(0, dp(4), 0, dp(14))
            iv.setOnClickListener {
                showBigImage(path)
            }
            container.addView(iv, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        val fields = listOf(
            "类型" to item.type,
            "货品编码" to item.goodsCode,
            "OE码" to item.oeCode,
            "车型" to item.carModel,
            "规格" to item.specification,
            "胶圈" to item.rubberRing,
            "盒子" to item.boxInfo,
            "备注" to item.notes
        ).filter { it.second.isNotBlank() }

        fields.forEachIndexed { index, (label, value) ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, dp(11), 0, dp(11))
            if (index != fields.size - 1) {
                row.background = ContextCompat.getDrawable(this, R.drawable.bg_divider_bottom)
            }

            val k = TextView(this)
            k.text = label
            k.textSize = 13f
            k.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
            row.addView(k, LinearLayout.LayoutParams(dp(80), ViewGroup.LayoutParams.WRAP_CONTENT))

            val v = TextView(this)
            v.text = value
            v.textSize = 14.5f
            v.setTextColor(ContextCompat.getColor(this, R.color.text_main))
            v.setTypeface(v.typeface, android.graphics.Typeface.BOLD)
            if (label.contains("编码") || label.contains("OE")) {
                v.typeface = android.graphics.Typeface.MONOSPACE
            }
            row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

            container.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }

        if (fields.isEmpty()) {
            val t = TextView(this)
            t.text = "没有详细信息"
            t.gravity = Gravity.CENTER
            t.setPadding(0, dp(40), 0, dp(40))
            container.addView(t)
        }

        val hint = TextView(this)
        hint.text = "提示：点击图片可放大查看"
        hint.textSize = 12f
        hint.gravity = Gravity.CENTER
        hint.setTextColor(Color.parseColor("#AAB3C5"))
        hint.setPadding(0, dp(10), 0, 0)
        container.addView(hint)
    }

    private fun showBigImage(path: String) {
        val dialog = AlertDialog.Builder(this).create()
        val frame = FrameLayout(this)
        frame.setBackgroundColor(Color.BLACK)
        val iv = ImageView(this)
        iv.setImageBitmap(FilterAdapter.decodeSampled(path, 1600))
        frame.addView(iv, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT
        ))
        dialog.setView(frame)
        dialog.show()
        iv.setOnClickListener { dialog.dismiss() }
    }

    private fun confirmDelete() {
        AlertDialog.Builder(this)
            .setTitle("删除确认")
            .setMessage("确定要删除这条记录吗？")
            .setPositiveButton("删除") { _, _ ->
                val id = currentDetailId ?: return@setPositiveButton
                items.find { it.id == id }?.imagePath?.let { File(it).delete() }
                items.removeAll { it.id == id }
                FilterStore.save(this, items)
                detailDialog?.dismiss()
                renderList()
                toast("已删除")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun copyCurrentOe() {
        val item = items.find { it.id == currentDetailId } ?: return
        if (item.oeCode.isBlank()) return
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("OE", item.oeCode))
        toast("已复制 OE码 ✓")
    }

    // ---------- 相机权限 ----------
    private fun ensureCameraPerm(action: () -> Unit) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            action()
        } else {
            pendingCameraAction = action
            cameraPermLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // ---------- 扫码 ----------
    private fun startScan(mode: String) {
        scanMode = mode
        ensureCameraPerm {
            try {
                scanLauncher.launch(ScanOptions().apply {
                    setDesiredBarcodeFormats(ScanOptions.ALL_CODE_TYPES)
                    setPrompt("对准条码自动识别")
                    setBeepEnabled(true)
                    setOrientationLocked(false)
                })
            } catch (e: ActivityNotFoundException) {
                toast("未找到可用的相机应用")
            } catch (e: Exception) {
                toast("无法启动相机：${e.message}")
            }
        }
    }

    // ---------- 图片处理 ----------
    private fun takePhoto() {
        ensureCameraPerm { doTakePhoto() }
    }

    private fun doTakePhoto() {
        try {
            val dir = File(cacheDir, "photos")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "capture_${System.currentTimeMillis()}.jpg")
            pendingCameraFile = f
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            cameraLauncher.launch(uri)
        } catch (e: ActivityNotFoundException) {
            toast("未找到可用的相机应用")
        } catch (e: Exception) {
            toast("无法启动相机：${e.message}")
        }
    }

    private fun processCapturedImage(temp: File) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(temp.absolutePath, bounds)
            var sample = 1
            while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
            val bmp = BitmapFactory.decodeFile(
                temp.absolutePath,
                BitmapFactory.Options().apply { inSampleSize = sample }
            ) ?: run { toast("图片处理失败"); return }
            val dest = FilterStore.newImageFile(this)
            FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, 72, it) }
            currentImagePath = dest.absolutePath
            refreshPreview()
        } catch (e: Exception) {
            toast("图片处理失败：${e.message}")
        }
    }

    private fun importImageFromUri(uri: Uri) {
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, bounds)
            }
            if (bounds.outWidth <= 0) { toast("图片读取失败"); return }
            var sample = 1
            while (bounds.outWidth / sample > 1800 || bounds.outHeight / sample > 1800) sample *= 2
            val bmp = contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            } ?: run { toast("图片读取失败"); return }
            val dest = FilterStore.newImageFile(this)
            FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, 72, it) }
            currentImagePath = dest.absolutePath
            refreshPreview()
        } catch (e: Exception) {
            toast("图片读取失败：${e.message}")
        }
    }

    // ---------- 导出 / 导入 ----------
    private fun startExport() {
        if (items.isEmpty()) { toast("暂无数据可导出"); return }
        pendingExportText = CsvUtil.exportRows(items)
        exportLauncher.launch(CsvUtil.todayName())
    }

    private fun startImport() {
        importLauncher.launch(arrayOf(
            "text/csv",
            "text/comma-separated-values",
            "text/plain",
            "text/*"
        ))
    }

    private fun importCsvFromUri(uri: Uri) {
        try {
            val text = contentResolver.openInputStream(uri)?.use {
                it.readBytes().toString(Charsets.UTF_8)
            } ?: return
            val newItems = CsvUtil.toItems(CsvUtil.parse(text))
            if (newItems.isEmpty()) { toast("未发现有效数据"); return }

            val existKeys = items.map { it.dedupeKey }.filter { it != "|" }.toHashSet()
            val fresh = mutableListOf<FilterItem>()
            var dupes = 0
            newItems.forEach { n ->
                if (n.dedupeKey != "|" && existKeys.contains(n.dedupeKey)) dupes++
                else { existKeys.add(n.dedupeKey); fresh.add(n) }
            }
            val msg = buildString {
                append("发现 ${fresh.size} 条新记录")
                if (dupes > 0) append("（$dupes 条重复已跳过）")
                append("，是否导入？")
            }
            AlertDialog.Builder(this)
                .setTitle("导入确认")
                .setMessage(msg)
                .setPositiveButton("导入") { _, _ ->
                    items.addAll(0, fresh)
                    FilterStore.save(this, items)
                    renderList()
                    toast("导入成功 ✓")
                }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            toast("导入失败：${e.message}")
        }
    }

    // ---------- 工具 ----------
    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
