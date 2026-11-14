package com.light.applocator

import android.graphics.drawable.Drawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class AppAdapter(
    private var items: List<AppInfo>,
    private val onLocate: (AppInfo) -> Unit
) : RecyclerView.Adapter<AppAdapter.VH>() {

    fun submit(list: List<AppInfo>) {
        items = list
        notifyDataSetChanged()
    }

    class VH(v: View) : RecyclerView.ViewHolder(v) {
        val icon: ImageView = v.findViewById(R.id.appIcon)
        val name: TextView = v.findViewById(R.id.appName)
        val pkg: TextView = v.findViewById(R.id.appPkg)
        val meta: TextView = v.findViewById(R.id.appMeta)
        val btn: TextView = v.findViewById(R.id.btnLocate)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val v = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_app, parent, false)
        return VH(v)
    }

    override fun getItemCount() = items.size

    override fun onBindViewHolder(h: VH, pos: Int) {
        val app = items[pos]
        val pm = h.itemView.context.packageManager
        h.icon.setImageDrawable(runCatching {
            pm.getApplicationIcon(app.packageName)
        }.getOrNull())
        h.name.text = app.label
        h.pkg.text = app.packageName
        h.meta.text = buildString {
            append("v${app.versionName}")
            if (app.isSystem) append(" · 系统")
            append(" · ${app.apkSize / 1024 / 1024}MB")
            app.location?.let { append(" · ").append(it) }
        }
        h.itemView.setOnClickListener { onLocate(app) }
        h.btn.setOnClickListener { onLocate(app) }
    }
}
