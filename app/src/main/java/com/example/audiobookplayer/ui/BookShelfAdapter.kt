package com.example.audiobookplayer.ui

import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide
import com.example.audiobookplayer.R
import com.example.audiobookplayer.model.AudioBook
import java.io.File

class BookShelfAdapter(
    private val onBookClick: (AudioBook) -> Unit
) : RecyclerView.Adapter<BookShelfAdapter.BookViewHolder>() {

    private val books = mutableListOf<AudioBook>()

    fun submitList(newBooks: List<AudioBook>) {
        books.clear()
        books.addAll(newBooks)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BookViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_book, parent, false)
        return BookViewHolder(view)
    }

    override fun onBindViewHolder(holder: BookViewHolder, position: Int) {
        holder.bind(books[position])
    }

    override fun getItemCount(): Int = books.size

    inner class BookViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val coverImage: ImageView = itemView.findViewById(R.id.iv_cover)
        private val titleText: TextView = itemView.findViewById(R.id.tv_title)
        private val authorText: TextView = itemView.findViewById(R.id.tv_author)
        private val chapterCountText: TextView = itemView.findViewById(R.id.tv_chapter_count)

        fun bind(book: AudioBook) {
            titleText.text = book.title
            authorText.text = book.author ?: book.artist ?: ""
            chapterCountText.text = "${book.chapterCount}集"

            // 加载封面
            if (book.coverPath != null) {
                Glide.with(itemView.context)
                    .load(File(book.coverPath))
                    .placeholder(R.drawable.ic_book_placeholder)
                    .error(R.drawable.ic_book_placeholder)
                    .into(coverImage)
            } else if (book.chapters.isNotEmpty() && !book.chapters.first().isLocal) {
                // 在线书源的封面
                Glide.with(itemView.context)
                    .load(book.coverPath)
                    .placeholder(R.drawable.ic_book_placeholder)
                    .error(R.drawable.ic_book_placeholder)
                    .into(coverImage)
            } else {
                coverImage.setImageResource(R.drawable.ic_book_placeholder)
            }

            itemView.setOnClickListener { onBookClick(book) }
        }
    }
}
