package com.filtermaster.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ActivityNotFoundException
import android.content.pm.PackageManager
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
    private lateinit var brandSelector: View
    private lateinit var tvBrandPicked: TextView
    private lateinit var etGoodsCode: EditText
    private lateinit var etAlias: EditText
    private lateinit var etOeCode: EditText
    private lateinit var etCarModel: EditText
    private lateinit var etSpec: EditText
    private lateinit var etLocation: EditText
    private lateinit var etRing: EditText
    private lateinit var etBox: EditText
    private lateinit var etNotes: EditText
    private lateinit var imgPreviewWrap: View
    private lateinit var ivPreview: ImageView
    private var editingId: Long? = null
    private var pickedBrand: String = ""
    private var currentImagePath: String? = null

    // 详情弹层
    private var detailDialog: BottomSheetDialog? = null
    private var currentDetailId: Long? = null

    private var scanMode = "edit"   // edit: 填OE码 | search: 填搜索框
    private var pendingCameraFile: File? = null
    private var pendingBackupFile: File? = null

    private val FILTER_TAGS: List<Pair<String, String>> =
        listOf("all" to "全部") + Brands.ALL.map { it to it }
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

    /** 备份：把打包好的 ZIP 写到用户选定位置 */
    private val backupSaveLauncher =
        registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
            val src = pendingBackupFile
            if (uri != null && src != null && src.exists()) {
                runCatching {
                    contentResolver.openOutputStream(uri)?.use { out ->
                        java.io.FileInputStream(src).use { it.copyTo(out) }
                    } ?: throw IllegalStateException("无法写入所选位置")
                }.onSuccess {
                    toast("已备份 ${items.size} 条记录 ✓")
                }.onFailure {
                    toast("备份失败：${it.message}")
                }
            }
            pendingBackupFile = null
        }

    /** 恢复：读取用户选定的 ZIP 备份 */
    private val backupOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { restoreFromUri(it) }
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
                // 选中：白底 + 蓝色粗体字，在蓝色渐变头部上对比最强
                v.background?.mutate()?.setTint(Color.WHITE)
                v.setTextColor(ContextCompat.getColor(this, R.color.primary_deep))
                v.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
                v.elevation = dp(3).toFloat()
            } else {
                v.background?.mutate()?.setTint(Color.parseColor("#1FFFFFFF"))
                v.setTextColor(Color.parseColor("#CCFFFFFF"))
                v.setTypeface(android.graphics.Typeface.DEFAULT)
                v.elevation = 0f
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
        findViewById<TextView>(R.id.btnBackup).setOnClickListener { showBackupSheet() }
    }

    // ---------- 列表渲染 ----------
    private fun renderList() {
        displayed.clear()
        displayed.addAll(items.asSequence()
            .filter { currentType == "all" || it.brand == currentType }
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
        brandSelector = view.findViewById(R.id.brandSelector)
        tvBrandPicked = view.findViewById(R.id.tvBrandPicked)
        etGoodsCode = view.findViewById(R.id.etGoodsCode)
        etAlias = view.findViewById(R.id.etAlias)
        etOeCode = view.findViewById(R.id.etOeCode)
        etCarModel = view.findViewById(R.id.etCarModel)
        etSpec = view.findViewById(R.id.etSpec)
        etLocation = view.findViewById(R.id.etLocation)
        etRing = view.findViewById(R.id.etRing)
        etBox = view.findViewById(R.id.etBox)
        etNotes = view.findViewById(R.id.etNotes)
        imgPreviewWrap = view.findViewById(R.id.imgPreviewWrap)
        ivPreview = view.findViewById(R.id.ivPreview)

        brandSelector.setOnClickListener { showBrandPicker() }

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

    // ---------- 品牌选择 ----------
    private fun showBrandPicker() {
        val options = arrayOf("（不指定）") + Brands.ALL.toTypedArray()
        val checked = if (pickedBrand.isBlank()) 0 else Brands.ALL.indexOf(pickedBrand) + 1
        AlertDialog.Builder(this)
            .setTitle("选择品牌")
            .setSingleChoiceItems(options, checked) { dlg, which ->
                setPickedBrand(if (which == 0) "" else Brands.ALL[which - 1])
                dlg.dismiss()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun setPickedBrand(brand: String) {
        pickedBrand = brand
        if (brand.isBlank()) {
            tvBrandPicked.text = "请选择品牌"
            tvBrandPicked.setTextColor(Color.parseColor("#A6AFC0"))
        } else {
            tvBrandPicked.text = brand
            tvBrandPicked.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        }
    }

    private fun openEditor(item: FilterItem?) {
        val dialog = ensureEditDialog()
        editingId = item?.id?.takeIf { it != 0L }
        editTitle.text = if (item == null) "新增滤芯" else "编辑滤芯"
        etGoodsCode.setText(item?.goodsCode.orEmpty())
        etAlias.setText(item?.alias.orEmpty())
        etOeCode.setText(item?.oeCode.orEmpty())
        etCarModel.setText(item?.carModel.orEmpty())
        etSpec.setText(item?.specification.orEmpty())
        etLocation.setText(item?.location.orEmpty())
        etRing.setText(item?.rubberRing.orEmpty())
        etBox.setText(item?.boxInfo.orEmpty())
        etNotes.setText(item?.notes.orEmpty())
        currentImagePath = item?.imagePath
        setPickedBrand(item?.brand.orEmpty())
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
        val alias = etAlias.text.toString().trim()
        val oe = etOeCode.text.toString().trim()
        val car = etCarModel.text.toString().trim()
        if (goods.isEmpty() && oe.isEmpty() && car.isEmpty() && alias.isEmpty()) {
            toast("请至少填写 编码 / 别称 / OE码 / 车型")
            return
        }
        val eid = editingId
        if (eid != null) {
            val idx = items.indexOfFirst { it.id == eid }
            if (idx >= 0) {
                val old = items[idx]
                items[idx] = old.copy(
                    brand = pickedBrand,
                    goodsCode = goods, alias = alias, oeCode = oe, carModel = car,
                    specification = etSpec.text.toString().trim(),
                    location = etLocation.text.toString().trim(),
                    rubberRing = etRing.text.toString().trim(),
                    boxInfo = etBox.text.toString().trim(),
                    notes = etNotes.text.toString().trim(),
                    imagePath = currentImagePath
                )
            }
        } else {
            items.add(0, FilterItem(
                id = System.currentTimeMillis(),
                brand = pickedBrand,
                goodsCode = goods, alias = alias, oeCode = oe, carModel = car,
                specification = etSpec.text.toString().trim(),
                location = etLocation.text.toString().trim(),
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
            "品牌" to item.brand,
            "货品编码" to item.goodsCode,
            "别称" to item.alias,
            "OE码" to item.oeCode,
            "车型" to item.carModel,
            "规格" to item.specification,
            "位置" to item.location,
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

    // ==================== 备份与恢复 ====================
    private fun showBackupSheet() {
        val dialog = BottomSheetDialog(this)
        val view = layoutInflater.inflate(R.layout.sheet_backup, null)
        dialog.setContentView(view)

        val summary = view.findViewById<TextView>(R.id.tvBackupSummary)
        summary.text = "当前共 ${items.size} 条记录" +
                items.count { !it.imagePath.isNullOrBlank() }.let { if (it > 0) "，其中 $it 条带照片" else "" }

        val cloudStatus = view.findViewById<TextView>(R.id.tvCloudStatus)
        fun refreshCloudStatus() {
            val cfg = CloudPrefs.load(this)
            if (cfg.isReady) {
                cloudStatus.text = "已配置 · ${cfg.user}"
                cloudStatus.setTextColor(ContextCompat.getColor(this, R.color.green))
            } else {
                cloudStatus.text = "未配置"
                cloudStatus.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
            }
        }
        refreshCloudStatus()

        view.findViewById<ImageButton>(R.id.btnBackupClose).setOnClickListener { dialog.dismiss() }
        view.findViewById<View>(R.id.btnLocalBackup).setOnClickListener {
            dialog.dismiss(); startLocalBackup()
        }
        view.findViewById<View>(R.id.btnLocalRestore).setOnClickListener {
            dialog.dismiss(); startLocalRestore()
        }
        view.findViewById<View>(R.id.btnCloudBackup).setOnClickListener {
            dialog.dismiss(); startCloudBackup()
        }
        view.findViewById<View>(R.id.btnCloudRestore).setOnClickListener {
            dialog.dismiss(); startCloudRestore()
        }
        view.findViewById<View>(R.id.btnCloudSetting).setOnClickListener {
            showCloudConfig { refreshCloudStatus() }
        }
        dialog.show()
    }

    // ---------- 本地 ----------
    private fun startLocalBackup() {
        if (items.isEmpty()) { toast("暂无数据可备份"); return }
        try {
            pendingBackupFile = BackupUtil.createBackup(this, items)
            backupSaveLauncher.launch(BackupUtil.backupFileName())
        } catch (e: Exception) {
            toast("打包失败：${e.message}")
        }
    }

    private fun startLocalRestore() {
        backupOpenLauncher.launch(arrayOf("application/zip", "application/octet-stream", "*/*"))
    }

    private fun restoreFromUri(uri: Uri) {
        try {
            val cached = contentResolver.openInputStream(uri)?.use {
                BackupUtil.cacheFrom(this, it, "restore.zip")
            } ?: run { toast("无法读取所选文件"); return }
            applyRestore(cached, "本地文件")
        } catch (e: Exception) {
            toast("恢复失败：${e.message}")
        }
    }

    /** 解析备份并弹窗让用户选择合并或覆盖 */
    private fun applyRestore(zip: File, sourceLabel: String) {
        val result = try {
            BackupUtil.readBackup(this, zip)
        } catch (e: Exception) {
            toast("恢复失败：${e.message}")
            return
        }
        if (result.items.isEmpty()) { toast("备份中没有记录"); return }

        val existKeys = items.map { it.dedupeKey }.filter { it != "|" }.toHashSet()
        val fresh = result.items.filter { it.dedupeKey == "|" || !existKeys.contains(it.dedupeKey) }
        val dupes = result.items.size - fresh.size

        val msg = buildString {
            append("来源：$sourceLabel\n")
            if (result.exportedAt.isNotBlank()) append("备份时间：${result.exportedAt}\n")
            append("包含记录：${result.items.size} 条")
            if (result.imageCount > 0) append("，照片 ${result.imageCount} 张")
            append("\n\n合并：新增 ${fresh.size} 条")
            if (dupes > 0) append("，跳过重复 $dupes 条")
            append("\n覆盖：清空现有 ${items.size} 条后写入 ${result.items.size} 条")
        }

        AlertDialog.Builder(this)
            .setTitle("恢复数据")
            .setMessage(msg)
            .setPositiveButton("合并") { _, _ ->
                items.addAll(0, fresh)
                FilterStore.save(this, items)
                renderList()
                toast("已合并 ${fresh.size} 条 ✓")
            }
            .setNeutralButton("覆盖") { _, _ ->
                AlertDialog.Builder(this)
                    .setTitle("确认覆盖")
                    .setMessage("现有 ${items.size} 条记录将被删除且无法找回，确定继续？")
                    .setPositiveButton("确定覆盖") { _, _ ->
                        items.clear()
                        items.addAll(result.items)
                        FilterStore.save(this, items)
                        renderList()
                        toast("已恢复 ${items.size} 条 ✓")
                    }
                    .setNegativeButton("取消", null)
                    .show()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ---------- 坚果云配置 ----------
    private fun showCloudConfig(onSaved: () -> Unit) {
        val view = layoutInflater.inflate(R.layout.dialog_cloud_config, null)
        val etUrl = view.findViewById<EditText>(R.id.etDavUrl)
        val etUser = view.findViewById<EditText>(R.id.etDavUser)
        val etPass = view.findViewById<EditText>(R.id.etDavPass)
        val etDir = view.findViewById<EditText>(R.id.etDavDir)

        val cfg = CloudPrefs.load(this)
        etUrl.setText(cfg.url)
        etUser.setText(cfg.user)
        etPass.setText(cfg.pass)
        etDir.setText(cfg.dir)

        AlertDialog.Builder(this)
            .setTitle("坚果云 / WebDAV 配置")
            .setView(view)
            .setPositiveButton("保存并测试") { _, _ ->
                val next = CloudPrefs.Config(
                    url = etUrl.text.toString().trim().ifBlank { CloudPrefs.DEFAULT_URL },
                    user = etUser.text.toString().trim(),
                    pass = etPass.text.toString(),
                    dir = etDir.text.toString().trim().ifBlank { CloudPrefs.DEFAULT_DIR }
                )
                if (!next.isReady) { toast("请完整填写地址、账户与应用密码"); return@setPositiveButton }
                CloudPrefs.save(this, next)
                onSaved()
                testCloud(next)
            }
            .setNeutralButton("清除") { _, _ ->
                CloudPrefs.clear(this)
                onSaved()
                toast("已清除云端配置")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun testCloud(cfg: CloudPrefs.Config) {
        val progress = showProgress("正在测试连接…")
        runAsync(
            work = {
                val client = CloudPrefs.clientOf(cfg)
                client.ensureDir(cfg.dir)
                client.testConnection(cfg.dir)
                client.listBackups(cfg.dir).size
            },
            done = { count ->
                progress.dismiss()
                toast("连接成功，云端已有 $count 个备份 ✓")
            },
            fail = { e ->
                progress.dismiss()
                showError("连接失败", e)
            }
        )
    }

    // ---------- 云备份 ----------
    private fun startCloudBackup() {
        val cfg = CloudPrefs.load(this)
        if (!cfg.isReady) { toast("请先配置坚果云账号"); showCloudConfig { }; return }
        if (items.isEmpty()) { toast("暂无数据可备份"); return }

        val progress = showProgress("正在上传备份…")
        val snapshot = items.toList()
        runAsync(
            work = {
                val zip = BackupUtil.createBackup(this, snapshot)
                val client = CloudPrefs.clientOf(cfg)
                client.upload(cfg.dir, zip)
                zip.name
            },
            done = { name ->
                progress.dismiss()
                AlertDialog.Builder(this)
                    .setTitle("云备份完成")
                    .setMessage("已上传 ${snapshot.size} 条记录\n\n文件名：$name\n位置：${cfg.dir}/")
                    .setPositiveButton("知道了", null)
                    .show()
            },
            fail = { e ->
                progress.dismiss()
                showError("上传失败", e)
            }
        )
    }

    private fun startCloudRestore() {
        val cfg = CloudPrefs.load(this)
        if (!cfg.isReady) { toast("请先配置坚果云账号"); showCloudConfig { }; return }

        val progress = showProgress("正在获取云端备份…")
        runAsync(
            work = { CloudPrefs.clientOf(cfg).listBackups(cfg.dir) },
            done = { list ->
                progress.dismiss()
                if (list.isEmpty()) {
                    toast("云端没有找到备份文件")
                    return@runAsync
                }
                val labels = list.map { f ->
                    val sizeText = if (f.size > 0) " · ${f.size / 1024} KB" else ""
                    f.name + sizeText
                }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("选择要恢复的备份")
                    .setItems(labels) { _, which -> downloadAndRestore(cfg, list[which].name) }
                    .setNegativeButton("取消", null)
                    .show()
            },
            fail = { e ->
                progress.dismiss()
                showError("获取列表失败", e)
            }
        )
    }

    private fun downloadAndRestore(cfg: CloudPrefs.Config, name: String) {
        val progress = showProgress("正在下载 $name …")
        runAsync(
            work = {
                val dir = File(cacheDir, "restore")
                if (!dir.exists()) dir.mkdirs()
                dir.listFiles()?.forEach { it.delete() }
                val dest = File(dir, name)
                CloudPrefs.clientOf(cfg).download(cfg.dir, name, dest)
                dest
            },
            done = { file ->
                progress.dismiss()
                applyRestore(file, "坚果云 · $name")
            },
            fail = { e ->
                progress.dismiss()
                showError("下载失败", e)
            }
        )
    }

    // ---------- 异步与提示 ----------
    private fun <T> runAsync(work: () -> T, done: (T) -> Unit, fail: (Throwable) -> Unit) {
        Thread {
            val result = runCatching(work)
            runOnUiThread {
                result.onSuccess(done).onFailure(fail)
            }
        }.start()
    }

    private fun showProgress(text: String): AlertDialog {
        val tv = TextView(this)
        tv.text = text
        tv.textSize = 14.5f
        tv.setPadding(dp(24), dp(24), dp(24), dp(24))
        tv.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        return AlertDialog.Builder(this)
            .setView(tv)
            .setCancelable(false)
            .create()
            .also { it.show() }
    }

    private fun showError(title: String, e: Throwable) {
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(e.message ?: e.toString())
            .setPositiveButton("知道了", null)
            .show()
    }

    // ---------- 工具 ----------
    private fun toast(msg: String) =
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
