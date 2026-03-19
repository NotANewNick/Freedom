package freedom.app.data.repository

import androidx.lifecycle.LiveData
import freedom.app.data.dao.IConfigDao
import freedom.app.data.entity.ConfigData

class ConfigRepository(private val trackerDao: IConfigDao) {

    fun addAverageTripDistance(distance: String): Long {
        return trackerDao.insertAverageTripDistance(ConfigData(0, distance, ""))
    }

    fun updateAverageTripDistance(distance: String): Int {
        return trackerDao.updateAverageTripDistance(ConfigData(0, distance, ""))
    }

    fun fetchAverageTripDistance(): String {
        return trackerDao.getAverageTripDistance()
    }

    fun addAverageTripMonth(month: String): Long {
        return trackerDao.insertAverageTripMonth(ConfigData(0, "", month))
    }

    fun updateAverageTripMonth(month: String): Int {
        return trackerDao.updateAverageTripMonth(ConfigData(0, "", month))
    }

    fun fetchAverageTripMonth(): String {
        return trackerDao.getAverageTripMonth()
    }

    fun updateMonthlyDistances(businessKm: Double, privateKm: Double) =
        trackerDao.updateMonthlyDistances(businessKm, privateKm)

    fun fetchMonthlyBusinessKm(): Double? = trackerDao.getMonthlyBusinessKm()

    fun fetchMonthlyPrivateKm(): Double? = trackerDao.getMonthlyPrivateKm()
}