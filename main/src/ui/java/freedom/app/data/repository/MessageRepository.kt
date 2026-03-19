package freedom.app.data.repository

import androidx.lifecycle.LiveData
import freedom.app.data.dao.IMessageDao
import freedom.app.data.entity.MessageData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MessageRepository(private val messageDao: IMessageDao) {

    val allMessages: LiveData<List<MessageData>> = messageDao.getAllMessages()

    fun getForContact(contactId: Long): LiveData<List<MessageData>> =
        messageDao.getForContact(contactId)

    fun insertMessage(message: MessageData, callback: (Long) -> Unit) {
        CoroutineScope(Dispatchers.IO).launch {
            val id = messageDao.insertMessage(message)
            withContext(Dispatchers.Main) { callback(id) }
        }
    }
}
