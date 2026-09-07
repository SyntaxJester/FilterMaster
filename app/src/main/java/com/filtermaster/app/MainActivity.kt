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
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.filtermaster.app.sheet.SheetImporter
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

    /** 表格导入：Excel / CSV */
    private val sheetOpenLauncher =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            uri?.let { startSheetImport(it) }
        }

    /** 图片识别录入：相册选图 */
    private val ocrAlbumLauncher =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            uri?.let { runOcrOnUri(it) }
        }

    /** 图片识别录入：拍照 */
    private var pendingOcrFile: File? = null
    private val ocrCameraLauncher =
        registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
            val f = pendingOcrFile
            pendingOcrFile = null
            if (ok && f != null && f.exists()) runOcrOnFile(f)
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
            view.findViewById<View>(R.id.btnCopyOe).setOnClickListener { showCopyMenu() }
            view.findViewById<View>(R.id.btnEdit).setOnClickListener {
                val cur = items.find { it.id == currentDetailId }
                detailDialog?.dismiss()
                cur?.let { openEditor(it) }
            }
            detailDialog = dialog
            // 弹层高度固定为屏幕 88%，长内容内部滚动，按钮始终可见
            dialog.behavior.peekHeight = (resources.displayMetrics.heightPixels * 0.88).toInt()
        }
        bindDetail(detailDialog!!, item)
        detailDialog!!.show()
    }

    private fun bindDetail(dialog: BottomSheetDialog, item: FilterItem) {
        fun <T : View> f(id: Int): T = dialog.findViewById(id)!!

        f<TextView>(R.id.tvDetailCode).text = item.goodsCode.ifBlank { "未编码" }

        val alias = f<TextView>(R.id.tvDetailAlias)
        if (item.alias.isBlank()) alias.visibility = View.GONE
        else {
            alias.visibility = View.VISIBLE
            alias.text = item.alias
        }

        val brandBadge = f<TextView>(R.id.tvDetailBrand)
        if (item.brand.isBlank()) brandBadge.visibility = View.GONE
        else {
            brandBadge.visibility = View.VISIBLE
            brandBadge.text = item.brand
            val (bg, fg) = Brands.colorsOf(item.brand)
            brandBadge.background?.mutate()?.setTint(ContextCompat.getColor(this, bg))
            brandBadge.setTextColor(ContextCompat.getColor(this, fg))
        }

        // 照片
        val imgCard = f<View>(R.id.detailImageCard)
        val imgPath = item.imagePath
        if (!imgPath.isNullOrBlank() && File(imgPath).exists()) {
            imgCard.visibility = View.VISIBLE
            f<ImageView>(R.id.ivDetailImage).setImageBitmap(
                FilterAdapter.decodeSampled(imgPath, 900)
            )
            imgCard.setOnClickListener { showBigImage(imgPath) }
        } else {
            imgCard.visibility = View.GONE
        }

        // OE 码
        val oeBlock = f<View>(R.id.oeBlock)
        if (item.oeCode.isBlank()) oeBlock.visibility = View.GONE
        else {
            oeBlock.visibility = View.VISIBLE
            f<TextView>(R.id.tvDetailOe).text = item.oeCode
            f<View>(R.id.btnOeCopyInline).setOnClickListener {
                copyText(item.oeCode, "已复制 OE码 ✓")
            }
        }

        // 车型
        val carBlock = f<View>(R.id.carBlock)
        if (item.carModel.isBlank()) carBlock.visibility = View.GONE
        else {
            carBlock.visibility = View.VISIBLE
            f<TextView>(R.id.tvDetailCar).text = item.carModel
        }

        // 规格 / 位置 / 胶圈 / 盒子：两列网格
        val grid = f<LinearLayout>(R.id.gridBlock)
        grid.removeAllViews()
        val pairs = listOf(
            "规格" to item.specification,
            "位置" to item.location,
            "胶圈" to item.rubberRing,
            "盒子" to item.boxInfo
        ).filter { it.second.isNotBlank() }
        pairs.chunked(2).forEach { pairRow ->
            val row = LinearLayout(this)
            row.orientation = LinearLayout.HORIZONTAL
            row.setPadding(0, 0, 0, dp(10))
            pairRow.forEachIndexed { i, (label, value) ->
                val cellLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                if (i == 1) cellLp.marginStart = dp(10)
                row.addView(buildGridCell(label, value), cellLp)
            }
            // 单数补一个占位，保持左半宽度一致
            if (pairRow.size == 1) {
                val spacer = View(this)
                row.addView(spacer, LinearLayout.LayoutParams(0, 1, 1f).also { it.marginStart = dp(10) })
            }
            grid.addView(row, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            ))
        }
        grid.visibility = if (pairs.isEmpty()) View.GONE else View.VISIBLE

        // 备注
        val noteBlock = f<View>(R.id.noteBlock)
        if (item.notes.isBlank()) noteBlock.visibility = View.GONE
        else {
            noteBlock.visibility = View.VISIBLE
            f<TextView>(R.id.tvDetailNote).text = item.notes
        }

        // 时间
        val time = f<TextView>(R.id.tvDetailTime)
        time.text = if (item.createdAt.isBlank()) ""
        else "录入于 " + item.createdAt.replace("T", " ").take(16)
    }

    private fun buildGridCell(label: String, value: String): View {
        val box = LinearLayout(this)
        box.orientation = LinearLayout.VERTICAL
        box.background = ContextCompat.getDrawable(this, R.drawable.bg_field)
        box.setPadding(dp(12), dp(10), dp(12), dp(11))

        val k = TextView(this)
        k.text = label
        k.textSize = 11.5f
        k.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
        k.setTypeface(android.graphics.Typeface.DEFAULT_BOLD)
        box.addView(k)

        val v = TextView(this)
        v.text = value
        v.textSize = 14f
        v.setTextColor(ContextCompat.getColor(this, R.color.text_main))
        v.setPadding(0, dp(4), 0, 0)
        box.addView(v)
        return box
    }

    /** 复制菜单：编码 / OE码 / 全部信息 */
    private fun showCopyMenu() {
        val item = items.find { it.id == currentDetailId } ?: return
        val options = mutableListOf<Pair<String, String>>()
        if (item.goodsCode.isNotBlank()) options.add("货品编码：${item.goodsCode}" to item.goodsCode)
        if (item.oeCode.isNotBlank()) options.add("OE码：${item.oeCode}" to item.oeCode)
        val full = listOf(
            "品牌" to item.brand, "货品编码" to item.goodsCode, "别称" to item.alias,
            "OE码" to item.oeCode, "车型" to item.carModel, "规格" to item.specification,
            "位置" to item.location, "胶圈" to item.rubberRing, "盒子" to item.boxInfo,
            "备注" to item.notes
        ).filter { it.second.isNotBlank() }.joinToString("\n") { "${it.first}：${it.second}" }
        options.add("全部信息" to full)

        if (options.size == 1) { copyText(full, "已复制全部信息 ✓"); return }
        AlertDialog.Builder(this)
            .setTitle("复制哪一项？")
            .setItems(options.map { it.first }.toTypedArray()) { _, which ->
                copyText(options[which].second, "已复制 ✓")
            }
            .setNegativeButton("取消", null)
            .show()
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
        view.findViewById<View>(R.id.btnImportSheet).setOnClickListener {
            dialog.dismiss(); pickSheetFile()
        }
        view.findViewById<View>(R.id.btnImportOcr).setOnClickListener {
            dialog.dismiss(); startOcrEntry()
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

    // ==================== 表格导入（Excel / CSV） ====================
    private fun pickSheetFile() {
        sheetOpenLauncher.launch(
            arrayOf(
                "application/vnd.ms-excel",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "text/csv",
                "text/comma-separated-values",
                "text/plain",
                "application/octet-stream",
                "*/*"
            )
        )
    }

    private fun startSheetImport(uri: Uri) {
        val name = queryDisplayName(uri) ?: "import.xls"
        val progress = showProgress("正在解析 $name …")
        runAsync(
            work = {
                val cached = contentResolver.openInputStream(uri)?.use {
                    BackupUtil.cacheFrom(this, it, name.ifBlank { "import.xls" })
                } ?: throw IllegalStateException("无法读取所选文件")
                SheetImporter.readTables(cached, name)
            },
            done = { tables ->
                progress.dismiss()
                when {
                    tables.isEmpty() -> toast("文件里没有找到可导入的数据")
                    tables.size == 1 -> showMappingDialog(tables[0], name)
                    else -> {
                        val labels = tables.map { "${it.sheetName}（${it.rows.size} 行）" }.toTypedArray()
                        AlertDialog.Builder(this)
                            .setTitle("选择工作表")
                            .setItems(labels) { _, which -> showMappingDialog(tables[which], name) }
                            .setNegativeButton("取消", null)
                            .show()
                    }
                }
            },
            fail = { e ->
                progress.dismiss()
                showError("解析失败", e)
            }
        )
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (idx >= 0 && c.moveToFirst()) c.getString(idx) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/')

    /** 展示自动识别结果，允许逐项调整后再导入 */
    private fun showMappingDialog(table: SheetImporter.Table, fileName: String) {
        val mapping = SheetImporter.autoMap(table)
        var defaultBrand = ""
        val view = layoutInflater.inflate(R.layout.dialog_sheet_mapping, null)
        val summary = view.findViewById<TextView>(R.id.tvMapSummary)
        val rowsBox = view.findViewById<LinearLayout>(R.id.mapRows)
        val preview = view.findViewById<TextView>(R.id.tvPreview)
        val brandRow = view.findViewById<TextView>(R.id.tvDefaultBrand)

        val recognized = SheetImporter.FIELD_LABELS.indices
            .count { SheetImporter.getField(mapping, it) >= 0 }
        summary.text = "文件：$fileName\n工作表：${table.sheetName}\n" +
                "数据行：${table.rows.size} 行，共 ${table.header.size} 列\n" +
                "已自动识别 $recognized 个字段"

        fun refreshBrandRow() {
            brandRow.text = if (defaultBrand.isBlank()) "统一品牌：（不设置）" else "统一品牌：$defaultBrand"
        }
        refreshBrandRow()
        brandRow.setOnClickListener {
            val options = arrayOf("（不设置）") + Brands.ALL.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("导入的记录统一设为哪个品牌？")
                .setItems(options) { _, which ->
                    defaultBrand = if (which == 0) "" else Brands.ALL[which - 1]
                    refreshBrandRow()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        val colOptions = arrayOf("（不导入）") + table.columnLabels.toTypedArray()

        fun refreshRows() {
            rowsBox.removeAllViews()
            SheetImporter.FIELD_LABELS.forEachIndexed { fieldIdx, label ->
                val col = SheetImporter.getField(mapping, fieldIdx)
                val row = LinearLayout(this)
                row.orientation = LinearLayout.HORIZONTAL
                row.gravity = Gravity.CENTER_VERTICAL
                row.setPadding(0, dp(5), 0, dp(5))
                row.isClickable = true

                val k = TextView(this)
                k.text = label
                k.textSize = 13.5f
                k.setTextColor(ContextCompat.getColor(this, R.color.text_main))
                row.addView(k, LinearLayout.LayoutParams(dp(66), ViewGroup.LayoutParams.WRAP_CONTENT))

                val v = TextView(this)
                v.textSize = 13f
                v.background = ContextCompat.getDrawable(this, R.drawable.bg_field)
                v.setPadding(dp(10), dp(8), dp(10), dp(8))
                v.maxLines = 1
                v.ellipsize = android.text.TextUtils.TruncateAt.END
                if (col >= 0) {
                    v.text = table.columnLabels.getOrNull(col) ?: "第${col + 1}列"
                    v.setTextColor(ContextCompat.getColor(this, R.color.primary))
                } else {
                    v.text = "（不导入）"
                    v.setTextColor(ContextCompat.getColor(this, R.color.text_sub))
                }
                row.addView(v, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

                val onPick = View.OnClickListener {
                    AlertDialog.Builder(this)
                        .setTitle("「$label」对应哪一列？")
                        .setItems(colOptions) { _, which ->
                            SheetImporter.setField(mapping, fieldIdx, which - 1)
                            refreshRows()
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                row.setOnClickListener(onPick)
                v.setOnClickListener(onPick)
                rowsBox.addView(row)
            }
        }
        refreshRows()

        preview.text = buildPreview(table)

        AlertDialog.Builder(this)
            .setTitle("确认导入设置")
            .setView(view)
            .setPositiveButton("开始导入") { _, _ -> doSheetImport(table, mapping, defaultBrand) }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun buildPreview(table: SheetImporter.Table): String = buildString {
        val cols = minOf(table.header.size, 4)
        append(table.header.take(cols).joinToString(" | ") { trunc(it, 10) })
        append("\n")
        append("─".repeat(30))
        table.rows.take(3).forEach { row ->
            append("\n")
            append((0 until cols).joinToString(" | ") { trunc(row.getOrNull(it).orEmpty(), 10) })
        }
        if (table.rows.size > 3) append("\n… 其余 ${table.rows.size - 3} 行")
        if (table.header.size > cols) append("\n（仅展示前 $cols 列）")
    }

    private fun trunc(s: String, n: Int): String {
        val t = s.replace("\n", " ").trim()
        return if (t.length <= n) t.padEnd(n) else t.take(n - 1) + "…"
    }

    private fun doSheetImport(
        table: SheetImporter.Table,
        mapping: SheetImporter.Mapping,
        defaultBrand: String
    ) {
        if (!mapping.hasAnyKeyField) {
            toast("请至少指定 货品编码 / OE码 / 车型 / 别称 中的一项")
            return
        }
        val result = SheetImporter.buildItems(table, mapping, defaultBrand)
        if (result.items.isEmpty()) {
            toast("按当前设置没有解析出有效记录")
            return
        }

        val existKeys = items.map { it.dedupeKey }.filter { it != "|" }.toHashSet()
        val fresh = result.items.filter { it.dedupeKey == "|" || !existKeys.contains(it.dedupeKey) }
        val dupes = result.items.size - fresh.size

        val sample = result.items.take(3).joinToString("\n") { item ->
            "· " + listOf(item.goodsCode, item.oeCode, item.carModel)
                .filter { it.isNotBlank() }.joinToString(" / ")
        }
        val msg = buildString {
            append("解析到 ${result.items.size} 条记录")
            if (result.skipped > 0) append("（跳过 ${result.skipped} 行空数据）")
            append("\n\n可新增：${fresh.size} 条")
            if (dupes > 0) append("，与现有重复：$dupes 条")
            append("\n\n示例：\n$sample")
        }

        AlertDialog.Builder(this)
            .setTitle("导入确认")
            .setMessage(msg)
            .setPositiveButton("导入 ${fresh.size} 条") { _, _ ->
                if (fresh.isEmpty()) { toast("没有需要新增的记录"); return@setPositiveButton }
                items.addAll(0, fresh)
                FilterStore.save(this, items)
                renderList()
                toast("已导入 ${fresh.size} 条 ✓")
            }
            .setNeutralButton("全部导入（含重复）") { _, _ ->
                items.addAll(0, result.items)
                FilterStore.save(this, items)
                renderList()
                toast("已导入 ${result.items.size} 条 ✓")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    // ==================== 图片识别录入（OCR） ====================
    private fun startOcrEntry() {
        AlertDialog.Builder(this)
            .setTitle("图片识别录入")
            .setItems(arrayOf("📷 拍照识别", "🖼️ 从相册选择")) { _, which ->
                if (which == 0) ensureCameraPerm { launchOcrCamera() }
                else ocrAlbumLauncher.launch("image/*")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun launchOcrCamera() {
        try {
            val dir = File(cacheDir, "photos")
            if (!dir.exists()) dir.mkdirs()
            val f = File(dir, "ocr_${System.currentTimeMillis()}.jpg")
            pendingOcrFile = f
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            ocrCameraLauncher.launch(uri)
        } catch (e: Exception) {
            toast("无法启动相机：${e.message}")
        }
    }

    private fun runOcrOnUri(uri: Uri) {
        try {
            val f = contentResolver.openInputStream(uri)?.use {
                BackupUtil.cacheFrom(this, it, "ocr_pick.jpg")
            } ?: run { toast("无法读取所选图片"); return }
            runOcrOnFile(f)
        } catch (e: Exception) {
            toast("读取图片失败：${e.message}")
        }
    }

    private fun runOcrOnFile(file: File) {
        val bmp = FilterAdapter.decodeSampled(file.absolutePath, 1600)
        if (bmp == null) { toast("图片无法解析"); return }

        val progress = showProgress("正在识别图片文字…")
        val recognizer = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
        recognizer.process(InputImage.fromBitmap(bmp, 0))
            .addOnSuccessListener { visionText ->
                progress.dismiss()
                recognizer.close()
                val raw = visionText.text
                if (raw.isBlank()) {
                    toast("没有识别到文字，换个角度或补光后再拍")
                    return@addOnSuccessListener
                }
                showOcrResult(OcrExtractor.extract(raw), raw, file)
            }
            .addOnFailureListener { e ->
                progress.dismiss()
                recognizer.close()
                showError("识别失败", e)
            }
    }

    /** 识别结果确认页：可改字段、选品牌、查看原始文本 */
    private fun showOcrResult(result: OcrExtractor.Result, rawText: String, image: File) {
        val view = layoutInflater.inflate(R.layout.dialog_ocr_result, null)
        val hint = view.findViewById<TextView>(R.id.tvOcrHint)
        val brandRow = view.findViewById<TextView>(R.id.tvOcrBrand)
        val etGoods = view.findViewById<EditText>(R.id.etOcrGoods)
        val etOe = view.findViewById<EditText>(R.id.etOcrOe)
        val etCar = view.findViewById<EditText>(R.id.etOcrCar)
        val etSpec = view.findViewById<EditText>(R.id.etOcrSpec)

        val filled = listOf(result.goodsCode, result.oeText, result.carModel, result.specification)
            .count { it.isNotBlank() }
        hint.text = "共识别到 ${result.rawLines.size} 行文字，自动填充了 $filled 个字段。\n" +
                "请核对后保存，识别有误可直接修改。"

        etGoods.setText(result.goodsCode)
        etOe.setText(result.oeText)
        etCar.setText(result.carModel)
        etSpec.setText(result.specification)

        var brand = result.brand
        fun refreshBrand() {
            brandRow.text = if (brand.isBlank()) "品牌：（点击选择）" else "品牌：$brand"
        }
        refreshBrand()
        brandRow.setOnClickListener {
            val options = arrayOf("（不设置）") + Brands.ALL.toTypedArray()
            AlertDialog.Builder(this)
                .setTitle("选择品牌")
                .setItems(options) { _, which ->
                    brand = if (which == 0) "" else Brands.ALL[which - 1]
                    refreshBrand()
                }
                .setNegativeButton("取消", null)
                .show()
        }

        view.findViewById<View>(R.id.btnOcrRaw).setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("识别到的全部文字")
                .setMessage(rawText)
                .setPositiveButton("关闭", null)
                .setNeutralButton("复制") { _, _ -> copyText(rawText, "已复制识别文字") }
                .show()
        }

        AlertDialog.Builder(this)
            .setTitle("确认识别结果")
            .setView(view)
            .setPositiveButton("保存记录") { _, _ ->
                saveOcrItem(
                    brand = brand,
                    goods = etGoods.text.toString().trim(),
                    oe = etOe.text.toString().trim(),
                    car = etCar.text.toString().trim(),
                    spec = etSpec.text.toString().trim(),
                    image = image
                )
            }
            .setNeutralButton("去完整表单") { _, _ ->
                // 带着识别结果打开编辑弹层，补充胶圈/盒子/位置等
                val draft = FilterItem(
                    brand = brand,
                    goodsCode = etGoods.text.toString().trim(),
                    oeCode = etOe.text.toString().trim(),
                    carModel = etCar.text.toString().trim(),
                    specification = etSpec.text.toString().trim()
                )
                openEditor(draft)
                currentImagePath = persistOcrImage(image)
                refreshPreview()
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /** 把识别用的临时图片转存为记录附图 */
    private fun persistOcrImage(src: File): String? = runCatching {
        val bmp = FilterAdapter.decodeSampled(src.absolutePath, 1400) ?: return null
        val dest = FilterStore.newImageFile(this)
        FileOutputStream(dest).use { bmp.compress(Bitmap.CompressFormat.JPEG, 72, it) }
        dest.absolutePath
    }.getOrNull()

    private fun saveOcrItem(
        brand: String, goods: String, oe: String, car: String, spec: String, image: File
    ) {
        if (goods.isEmpty() && oe.isEmpty() && car.isEmpty()) {
            toast("请至少填写 编码 / OE码 / 车型")
            return
        }
        val dup = items.firstOrNull {
            (goods.isNotEmpty() && it.goodsCode == goods) ||
                    (oe.isNotEmpty() && it.oeCode == oe)
        }
        val save = {
            items.add(0, FilterItem(
                id = System.currentTimeMillis(),
                brand = brand,
                goodsCode = goods,
                oeCode = oe,
                carModel = car,
                specification = spec,
                imagePath = persistOcrImage(image),
                createdAt = java.text.SimpleDateFormat(
                    "yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US
                ).format(java.util.Date())
            ))
            FilterStore.save(this, items)
            renderList()
            toast("已录入 ✓")
        }
        if (dup != null) {
            AlertDialog.Builder(this)
                .setTitle("可能重复")
                .setMessage("已存在编码「${dup.goodsCode}」的记录，仍要新增吗？")
                .setPositiveButton("仍然新增") { _, _ -> save() }
                .setNegativeButton("取消", null)
                .show()
        } else save()
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

    private fun copyText(text: String, msg: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("copied", text))
        toast(msg)
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
