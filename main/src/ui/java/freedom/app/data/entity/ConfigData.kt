package freedom.app.data.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "config_data")
data class ConfigData(

    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    var Id: Long,
    @ColumnInfo(name = "average_trips_distance")
    var averageTripDistance: String?,
    @ColumnInfo(name = "average_trips_month")
    var averageTripsMonth: String?,
    @ColumnInfo(name = "monthly_business_km")
    var monthlyBusinessKm: Double? = null,
    @ColumnInfo(name = "monthly_private_km")
    var monthlyPrivateKm: Double? = null,
)