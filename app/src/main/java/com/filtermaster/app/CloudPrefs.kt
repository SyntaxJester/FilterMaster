package com.filtermaster.app

import android.content.Context

/**
 * 坚果云 / WebDAV 账号配置存储。
 *
 * ⚠️ 应用密码以明文存于应用私有 SharedPreferences（其它 App 无法读取，
 *    但 root 设备或备份提取仍可见）。坚果云的「应用密码」可随时在网页端撤销，
 *    建议专门为本应用生成一个，不要复用登录密码。
 */
object CloudPrefs {

    private const val PREF = "filter_master_cloud"
    private const val K_URL = "dav_url"
    private const val K_USER = "dav_user"
    private const val K_PASS = "dav_pass"
    private const val K_DIR = "dav_dir"

    const val DEFAULT_URL = "https://dav.jianguoyun.com/dav/"
    const val DEFAULT_DIR = "FilterMaster"

    data class Config(
        val url: String = DEFAULT_URL,
        val user: String = "",
        val pass: String = "",
        val dir: String = DEFAULT_DIR
    ) {
        val isReady: Boolean
            get() = url.isNotBlank() && user.isNotBlank() && pass.isNotBlank()
    }

    fun load(context: Context): Config {
        val sp = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
        return Config(
            url = sp.getString(K_URL, DEFAULT_URL).orEmpty().ifBlank { DEFAULT_URL },
            user = sp.getString(K_USER, "").orEmpty(),
            pass = sp.getString(K_PASS, "").orEmpty(),
            dir = sp.getString(K_DIR, DEFAULT_DIR).orEmpty().ifBlank { DEFAULT_DIR }
        )
    }

    fun save(context: Context, c: Config) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit()
            .putString(K_URL, c.url.trim())
            .putString(K_USER, c.user.trim())
            .putString(K_PASS, c.pass)
            .putString(K_DIR, c.dir.trim().trim('/'))
            .apply()
    }

    fun clear(context: Context) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE).edit().clear().apply()
    }

    fun clientOf(c: Config) = WebDavClient(c.url, c.user, c.pass)
}
