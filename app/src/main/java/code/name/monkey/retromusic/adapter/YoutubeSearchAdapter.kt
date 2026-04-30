package code.name.monkey.retromusic.adapter

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.network.YoutubeTrack
import com.bumptech.glide.Glide

class YoutubeSearchAdapter(
    private val onPlay: (YoutubeTrack) -> Unit,
    private val onDownload: (YoutubeTrack) -> Unit
) : RecyclerView.Adapter<YoutubeSearchAdapter.ViewHolder>() {

    private var tracks = listOf<YoutubeTrack>()

    fun submitList(list: List<YoutubeTrack>) {
        tracks = list
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_youtube_track, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        holder.bind(tracks[position])
    }

    override fun getItemCount() = tracks.size

    inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val thumbnail: ImageView = view.findViewById(R.id.ivThumbnail)
        private val title: TextView = view.findViewById(R.id.tvTitle)
        private val artist: TextView = view.findViewById(R.id.tvArtist)
        private val duration: TextView = view.findViewById(R.id.tvDuration)
        private val btnPlay: ImageButton = view.findViewById(R.id.btnPlay)
        private val btnDownload: ImageButton = view.findViewById(R.id.btnDownload)

        fun bind(track: YoutubeTrack) {
            title.text = track.title
            artist.text = track.artist
            duration.text = formatDuration(track.duration)
            Glide.with(itemView.context)
                .load(track.thumbnailUrl)
                .placeholder(R.drawable.default_audio_art)
                .into(thumbnail)
            btnPlay.setOnClickListener { onPlay(track) }
            btnDownload.setOnClickListener { onDownload(track) }
            itemView.setOnClickListener { onPlay(track) }
        }

        private fun formatDuration(seconds: Long): String {
            val m = seconds / 60
            val s = seconds % 60
            return "%d:%02d".format(m, s)
        }
    }
}
