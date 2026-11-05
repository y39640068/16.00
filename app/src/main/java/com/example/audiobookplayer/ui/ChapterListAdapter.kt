package com.example.audiobookplayer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.example.audiobookplayer.R
import com.example.audiobookplayer.model.AudioChapter

class ChapterListAdapter(
    private val onChapterClick: (Int) -> Unit
) : RecyclerView.Adapter<ChapterListAdapter.ChapterViewHolder>() {

    private val chapters = mutableListOf<AudioChapter>()
    private var currentPlayingIndex = -1

    fun submitList(newChapters: List<AudioChapter>) {
        chapters.clear()
        chapters.addAll(newChapters)
        notifyDataSetChanged()
    }

    fun setCurrentPlaying(index: Int) {
        val old = currentPlayingIndex
        currentPlayingIndex = index
        if (old != -1) notifyItemChanged(old)
        if (index != -1) notifyItemChanged(index)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ChapterViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chapter, parent, false)
        return ChapterViewHolder(view)
    }

    override fun onBindViewHolder(holder: ChapterViewHolder, position: Int) {
        holder.bind(chapters[position], position)
    }

    override fun getItemCount(): Int = chapters.size

    inner class ChapterViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val titleText: TextView = itemView.findViewById(R.id.tv_chapter_title)
        private val indexText: TextView = itemView.findViewById(R.id.tv_chapter_index)

        fun bind(chapter: AudioChapter, position: Int) {
            titleText.text = chapter.title
            indexText.text = "${position + 1}"

            if (position == currentPlayingIndex) {
                titleText.setTextColor(itemView.context.getColor(R.color.teal_700))
            } else {
                titleText.setTextColor(itemView.context.getColor(R.color.black))
            }

            itemView.setOnClickListener { onChapterClick(position) }
        }
    }
}
