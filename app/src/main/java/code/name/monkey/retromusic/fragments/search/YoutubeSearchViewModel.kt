package code.name.monkey.retromusic.fragments.search

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import code.name.monkey.retromusic.network.YoutubeSearchService
import code.name.monkey.retromusic.network.YoutubeTrack
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class YoutubeSearchViewModel : ViewModel() {

    private val _results = MutableLiveData<List<YoutubeTrack>>()
    val results: LiveData<List<YoutubeTrack>> = _results

    private val _loading = MutableLiveData<Boolean>()
    val loading: LiveData<Boolean> = _loading

    private val _error = MutableLiveData<String?>()
    val error: LiveData<String?> = _error

    private var searchJob: Job? = null

    fun search(query: String) {
        if (query.length < 3) return
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(800)
            _loading.value = true
            _error.value = null
            val result = YoutubeSearchService.search(query)
            _results.value = result
            _loading.value = false
            if (result.isEmpty()) _error.value = "Sonuç bulunamadı"
        }
    }

    fun clear() {
        _results.value = emptyList()
        searchJob?.cancel()
    }
}
