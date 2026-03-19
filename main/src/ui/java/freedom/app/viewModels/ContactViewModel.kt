package freedom.app.viewModels

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import freedom.app.data.entity.ContactData
import freedom.app.data.repository.ContactRepository
import freedom.app.data.room.FreedomDatabase
import freedom.app.security.PasskeySession
import kotlinx.coroutines.launch

class ContactViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = ContactRepository(
        FreedomDatabase.getDataseClient(application).contactDao()
    )

    val allContacts: LiveData<List<ContactData>> = repository.allContacts

    fun insert(contact: ContactData) = viewModelScope.launch { repository.insert(encryptKeys(contact)) }

    suspend fun insertSuspend(contact: ContactData) = repository.insert(encryptKeys(contact))

    private fun encryptKeys(contact: ContactData): ContactData {
        fun enc(v: String) = if (v.isEmpty()) v else (PasskeySession.encryptField(v) ?: v)
        return contact.copy(
            sendKey0 = enc(contact.sendKey0),
            sendKey1 = enc(contact.sendKey1),
            sendKey2 = enc(contact.sendKey2),
            recvKey0 = enc(contact.recvKey0),
            recvKey1 = enc(contact.recvKey1),
            recvKey2 = enc(contact.recvKey2)
        )
    }

    fun delete(contact: ContactData) = viewModelScope.launch { repository.delete(contact) }
}
