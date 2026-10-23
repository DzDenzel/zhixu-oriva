package com.planlock.feature.plan

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.planlock.app.record.entity.ScheduleSnapshotEntity

internal fun openScheduleMap(context: Context, schedule: ScheduleSnapshotEntity) {
    val coordinate = if (schedule.locationLat != null && schedule.locationLon != null) "${schedule.locationLat},${schedule.locationLon}" else null
    val query = if (coordinate == null) schedule.location.orEmpty() else "$coordinate(${schedule.location.orEmpty()})"
    val uri = Uri.parse("geo:${coordinate ?: "0,0"}?q=${Uri.encode(query)}")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW,uri)) }
        .onFailure { android.widget.Toast.makeText(context,"未找到可打开的地图应用",android.widget.Toast.LENGTH_SHORT).show() }
}
