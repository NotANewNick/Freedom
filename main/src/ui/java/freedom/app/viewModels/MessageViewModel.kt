package freedom.app.viewModels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import freedom.app.data.entity.MessageData
import freedom.app.data.repository.MessageRepository
import freedom.app.data.room.FreedomDatabase

class MessageViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: MessageRepository
    val allMessages: LiveData<List<MessageData>>

    init {
        val dao = FreedomDatabase.getDataseClient(application).messageDao()
        repository = MessageRepository(dao)
        allMessages = repository.allMessages
    }

    fun getForContact(contactId: Long): LiveData<List<MessageData>> =
        repository.getForContact(contactId)

    fun insertMessage(message: MessageData, callback: (Long) -> Unit) {
        repository.insertMessage(message, callback)
    }
}
