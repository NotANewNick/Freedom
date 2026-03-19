package freedom.app.data.repository

import androidx.lifecycle.LiveData
import freedom.app.data.dao.IContactDao
import freedom.app.data.entity.ContactData

class ContactRepository(private val dao: IContactDao) {

    val allContacts: LiveData<List<ContactData>> = dao.getAll()

    suspend fun insert(contact: ContactData) = dao.insert(contact)

    suspend fun delete(contact: ContactData) = dao.delete(contact)
}
