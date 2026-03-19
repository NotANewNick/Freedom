package freedom.app.data.dao

import androidx.lifecycle.LiveData
import androidx.room.*
import freedom.app.data.entity.ConfigData

@Dao
interface IConfigDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAverageTripDistance(distance: ConfigData) : Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAverageTripMonth(distance: ConfigData) : Long

    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun updateAverageTripDistance(distance: ConfigData) : Int

    @Update(onConflict = OnConflictStrategy.REPLACE)
    fun updateAverageTripMonth(distance: ConfigData) : Int

    @Query("SELECT average_trips_distance FROM config_data")
    fun getAverageTripDistance() : String

    @Query("SELECT average_trips_month FROM config_data")
    fun getAverageTripMonth() : String

    @Query("UPDATE config_data SET monthly_business_km = :businessKm, monthly_private_km = :privateKm")
    fun updateMonthlyDistances(businessKm: Double, privateKm: Double)

    @Query("SELECT monthly_business_km FROM config_data LIMIT 1")
    fun getMonthlyBusinessKm(): Double?

    @Query("SELECT monthly_private_km FROM config_data LIMIT 1")
    fun getMonthlyPrivateKm(): Double?
}