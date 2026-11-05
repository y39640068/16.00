package com.example.audiobookplayer.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.audiobookplayer.databinding.ActivityPluginManageBinding
import com.example.audiobookplayer.parser.SstsPluginEngine

class PluginManageActivity : AppCompatActivity() {
    private lateinit var binding: ActivityPluginManageBinding

    class SourceViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val name: TextView = itemView.findViewById(android.R.id.text1)
        val desc: TextView = itemView.findViewById(android.R.id.text2)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityPluginManageBinding.inflate(layoutInflater)
        setContentView(binding.root)
        setSupportActionBar(binding.toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        supportActionBar?.title = "书源插件"

        val sources = SstsPluginEngine.getInstance(this).getAllSources()
        binding.recyclerViewSources.layoutManager = LinearLayoutManager(this)

        if (sources.isEmpty()) {
            binding.tvEmpty.visibility = View.VISIBLE
            binding.recyclerViewSources.visibility = View.GONE
        } else {
            binding.tvEmpty.visibility = View.GONE
            binding.recyclerViewSources.visibility = View.VISIBLE
            binding.recyclerViewSources.adapter = object : RecyclerView.Adapter<SourceViewHolder>() {
                override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): SourceViewHolder {
                    val view = LayoutInflater.from(parent.context)
                        .inflate(android.R.layout.simple_list_item_2, parent, false)
                    return SourceViewHolder(view)
                }

                override fun onBindViewHolder(holder: SourceViewHolder, position: Int) {
                    val source = sources[position]
                    holder.name.text = source.name
                    holder.desc.text = source.description
                }

                override fun getItemCount() = sources.size
            }
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}
