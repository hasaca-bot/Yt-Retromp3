/*
 * Copyright (c) 2020 Hemanth Savarla.
 *
 * Licensed under the GNU General Public License v3
 *
 * This is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * See the GNU General Public License for more details.
 *
 */
package code.name.monkey.retromusic.fragments.search

import android.app.Activity.RESULT_OK
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.*
import android.view.inputmethod.InputMethodManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.getSystemService
import androidx.core.view.*
import androidx.core.widget.doAfterTextChanged
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.transition.TransitionManager
import code.name.monkey.retromusic.R
import code.name.monkey.retromusic.adapter.SearchAdapter
import code.name.monkey.retromusic.databinding.FragmentSearchBinding
import code.name.monkey.retromusic.extensions.*
import code.name.monkey.retromusic.fragments.base.AbsMainActivityFragment
import code.name.monkey.retromusic.util.PreferenceUtil
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.shape.MaterialShapeDrawable
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.transition.MaterialFadeThrough
import kotlinx.coroutines.Job
import androidx.lifecycle.ViewModelProvider
import code.name.monkey.retromusic.adapter.YoutubeSearchAdapter
import code.name.monkey.retromusic.network.YoutubeSearchService
import code.name.monkey.retromusic.network.YoutubeTrack
import android.widget.Toast
import net.yslibrary.android.keyboardvisibilityevent.KeyboardVisibilityEvent
import java.util.*
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SearchFragment : AbsMainActivityFragment(R.layout.fragment_search),
    ChipGroup.OnCheckedStateChangeListener {

    private var mediaPlayer: android.media.MediaPlayer? = null

    companion object {
        const val QUERY = "query"
    }

    private var _binding: FragmentSearchBinding? = null
    private val binding get() = _binding!!

    private lateinit var searchAdapter: SearchAdapter
    private var query: String? = null

    private var job: Job? = null
    private lateinit var youtubeAdapter: YoutubeSearchAdapter
    private lateinit var youtubeViewModel: YoutubeSearchViewModel

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        enterTransition = MaterialFadeThrough().addTarget(view)
        reenterTransition = MaterialFadeThrough().addTarget(view)
        _binding = FragmentSearchBinding.bind(view)
        mainActivity.setSupportActionBar(binding.toolbar)
        libraryViewModel.clearSearchResult()
        setupRecyclerView()

        binding.toolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.voiceSearch.setOnClickListener { startMicSearch() }
        binding.clearText.setOnClickListener {
            binding.searchView.clearText()
            searchAdapter.swapDataSet(listOf())
        }
        binding.searchView.apply {
            doAfterTextChanged {
                if (!it.isNullOrEmpty())
                    search(it.toString())
                else {
                    TransitionManager.beginDelayedTransition(binding.appBarLayout)
                    binding.voiceSearch.isVisible = true
                    binding.clearText.isGone = true
                }
            }
            focusAndShowKeyboard()
        }
        binding.keyboardPopup.apply {
            accentColor()
            setOnClickListener {
                binding.searchView.focusAndShowKeyboard()
            }
        }
        if (savedInstanceState != null) {
            query = savedInstanceState.getString(QUERY)
        }
        libraryViewModel.getSearchResult().observe(viewLifecycleOwner) {
            showData(it)
        }
        setupChips()
        setupYoutubeSearch()
        YoutubeSearchService.init()
        postponeEnterTransition()
        view.doOnPreDraw {
            startPostponedEnterTransition()
        }
        libraryViewModel.getFabMargin().observe(viewLifecycleOwner) {
            binding.keyboardPopup.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = it
            }
        }
        KeyboardVisibilityEvent.setEventListener(requireActivity(), viewLifecycleOwner) {
            if (it) {
                binding.keyboardPopup.isGone = true
            } else {
                binding.keyboardPopup.show()
            }
        }
        binding.appBarLayout.statusBarForeground =
            MaterialShapeDrawable.createWithElevationOverlay(requireContext())
    }

    private fun setupChips() {
        val chips = binding.searchFilterGroup.children.map { it as Chip }
        if (!PreferenceUtil.materialYou) {
            val states = arrayOf(
                intArrayOf(-android.R.attr.state_checked),
                intArrayOf(android.R.attr.state_checked)
            )
            val colors = intArrayOf(
                android.R.color.transparent,
                accentColor().addAlpha(0.5F)
            )
            chips.forEach {
                it.chipBackgroundColor = ColorStateList(states, colors)
            }
        }
        binding.searchFilterGroup.setOnCheckedStateChangeListener(this)
    }

    private fun showData(data: List<Any>) {
        if (data.isNotEmpty()) {
            searchAdapter.swapDataSet(data)
        } else {
            searchAdapter.swapDataSet(ArrayList())
        }
        binding.empty.isVisible = data.isEmpty() &&
            !binding.searchView.text.isNullOrEmpty()
    }

    private fun checkForMargins() {
        if (mainActivity.isBottomNavVisible) {
            binding.recyclerView.updateLayoutParams<ViewGroup.MarginLayoutParams> {
                bottomMargin = dip(R.dimen.bottom_nav_height)
            }
        }
    }

    private fun setupRecyclerView() {
        searchAdapter = SearchAdapter(requireActivity(), emptyList())
        searchAdapter.registerAdapterDataObserver(object : RecyclerView.AdapterDataObserver() {
            override fun onChanged() {
                super.onChanged()
                binding.empty.isVisible = searchAdapter.itemCount < 1
            }
        })
        binding.recyclerView.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = searchAdapter
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    super.onScrolled(recyclerView, dx, dy)
                    if (dy > 0) {
                        binding.keyboardPopup.shrink()
                    } else if (dy < 0) {
                        binding.keyboardPopup.extend()
                    }
                }
            })
        }
    }

    private fun search(query: String) {
        this.query = query
        TransitionManager.beginDelayedTransition(binding.appBarLayout)
        binding.voiceSearch.isGone = query.isNotEmpty()
        binding.clearText.isVisible = query.isNotEmpty()
        val filter = getFilter()
        job?.cancel()
        job = libraryViewModel.search(query, filter)
        if (query.length >= 2) {
            youtubeViewModel.search(query)
        } else {
            youtubeViewModel.clear()
        }
    }

    private fun getFilter(): Filter {
        return when (binding.searchFilterGroup.checkedChipId) {
            R.id.chip_audio -> Filter.SONGS
            R.id.chip_artists -> Filter.ARTISTS
            R.id.chip_albums -> Filter.ALBUMS
            R.id.chip_album_artists -> Filter.ALBUM_ARTISTS
            R.id.chip_genres -> Filter.GENRES
            R.id.chip_playlists -> Filter.PLAYLISTS
            else -> Filter.NO_FILTER
        }
    }

    private fun startMicSearch() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        intent.putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
        )
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        intent.putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.speech_prompt))
        try {
            speechInputLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            e.printStackTrace()
            showToast(getString(R.string.speech_not_supported))
        }
    }

    private val speechInputLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                val spokenText: String? =
                    result?.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0)
                binding.searchView.setText(spokenText)
            }
        }

    override fun onResume() {
        super.onResume()
        checkForMargins()
    }

    override fun onDestroyView() {
        hideKeyboard(view)
        mediaPlayer?.release()
        super.onDestroyView()
        _binding = null
    }

    override fun onPause() {
        super.onPause()
        hideKeyboard(view)
        mediaPlayer?.pause()
    }

    private fun hideKeyboard(view: View?) {
        if (view != null) {
            val imm = requireContext().getSystemService<InputMethodManager>()
            imm?.hideSoftInputFromWindow(view.windowToken, 0)
        }
    }

    override fun onCheckedChanged(group: ChipGroup, checkedIds: MutableList<Int>) {
        search(binding.searchView.text.toString())
    }

    override fun onCreateMenu(menu: Menu, menuInflater: MenuInflater) {}

    override fun onMenuItemSelected(menuItem: MenuItem) = false

    private fun setupYoutubeSearch() {
        youtubeViewModel = ViewModelProvider(this)[YoutubeSearchViewModel::class.java]
        youtubeAdapter = YoutubeSearchAdapter(
            onPlay = { track -> playYoutubeTrack(track) },
            onDownload = { track -> downloadYoutubeTrack(track) }
        )

        binding.rvYoutubeResults.apply {
            adapter = youtubeAdapter
            layoutManager = LinearLayoutManager(requireContext())
        }

        youtubeViewModel.results.observe(viewLifecycleOwner) { tracks ->
            youtubeAdapter.submitList(tracks)
            binding.rvYoutubeResults.visibility =
                if (tracks.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
            binding.tvYoutubeHeader.visibility =
                if (tracks.isNotEmpty()) android.view.View.VISIBLE else android.view.View.GONE
        }

        youtubeViewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.youtubeProgressBar.visibility =
                if (loading) android.view.View.VISIBLE else android.view.View.GONE
        }
    }

    private fun playYoutubeTrack(track: YoutubeTrack) {
        Toast.makeText(requireContext(), "▶ Bağlanıyor...", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            try {
                val streamUrl = withContext(Dispatchers.IO) {
                    YoutubeSearchService.getAudioStreamUrl(track.url)
                }
                if (!streamUrl.isNullOrEmpty()) {
                    mediaPlayer?.release()
                    mediaPlayer = android.media.MediaPlayer().apply {
                        setDataSource(
                            requireContext(),
                            android.net.Uri.parse(streamUrl),
                            mapOf(
                                "User-Agent" to "Mozilla/5.0 (Linux; Android 11) AppleWebKit/537.36 Chrome/90.0",
                                "Referer" to "https://www.youtube.com/"
                            )
                        )
                        setAudioAttributes(
                            android.media.AudioAttributes.Builder()
                                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                                .build()
                        )
                        prepareAsync()
                        setOnPreparedListener {
                            it.start()
                            Toast.makeText(requireContext(), "🎶 Oynatılıyor!", Toast.LENGTH_SHORT).show()
                        }
                        setOnErrorListener { _, _, extra ->
                            Toast.makeText(requireContext(), "❌ Oynatma Hatası ($extra)", Toast.LENGTH_SHORT).show()
                            true
                        }
                    }
                } else {
                    Toast.makeText(requireContext(), "❌ Ses bağlantısı bulunamadı", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(requireContext(), "❌ Hata: ${e.localizedMessage}", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun downloadYoutubeTrack(track: YoutubeTrack) {
        Toast.makeText(requireContext(), "⬇ İndiriliyor: ${track.title}", Toast.LENGTH_SHORT).show()
        code.name.monkey.retromusic.service.YoutubeDownloadService.startDownload(
            requireContext(), track
        )
    }
}

enum class Filter {
    SONGS,
    ARTISTS,
    ALBUMS,
    ALBUM_ARTISTS,
    GENRES,
    PLAYLISTS,
    NO_FILTER
}

fun TextInputEditText.clearText() {
    text = null
}
