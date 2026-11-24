
package com.android.healthconnect.phone.je584bcf40a9b367d94eff78dae6fc17b

import android.app.DatePickerDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.text.InputType
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.roundToLong
import kotlin.random.Random

class MainActivity : AppCompatActivity() {

    private val permissions = setOf(
        HealthPermission.getReadPermission(StepsRecord::class),
        HealthPermission.getWritePermission(StepsRecord::class)
    )

    private var client: HealthConnectClient? = null

    private lateinit var statusView: TextView
    private lateinit var totalView: TextView
    private lateinit var dateInput: EditText
    private lateinit var stepsInput: EditText
    private lateinit var startTimeInput: EditText
    private lateinit var endTimeInput: EditText

    // Selected date (default: today)
    private var selectedDate: LocalDate = LocalDate.now()

    private val dateFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm")
    private val secondsFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    private val permissionLauncher = registerForActivityResult(
        PermissionController.createRequestPermissionResultContract()
    ) { granted ->

        if (granted.containsAll(permissions)) {
            setStatus("Health Connect permissions granted")
            refreshTotal()
        } else {
            setStatus("Health Connect permissions denied")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        selectedDate = LocalDate.now()

        setContentView(buildUi())

        initHealthConnect()
    }

    // ---------------------------------------------------------
    // UI
    // ---------------------------------------------------------

    private fun buildUi(): LinearLayout {

        val padding = (16 * resources.displayMetrics.density).toInt()

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding, padding * 2, padding, padding)

            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }

        statusView = TextView(this).apply {
            text = "Starting Health Connect..."
            textSize = 14f
        }

        totalView = TextView(this).apply {
            text = "Selected date steps: -"
            textSize = 20f
        }

        // Date selection input
        dateInput = EditText(this).apply {
            hint = "Select date"
            inputType = InputType.TYPE_NULL
            isFocusable = false
            isClickable = true
            setText(selectedDate.format(dateFormatter))

            setOnClickListener {
                showDatePicker()
            }
        }

        stepsInput = EditText(this).apply {
            hint = "Total steps (e.g. 1000)"
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        startTimeInput = EditText(this).apply {
            hint = "Start time (HH:mm)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText("07:00")
        }

        endTimeInput = EditText(this).apply {
            hint = "End time (HH:mm)"
            inputType = InputType.TYPE_CLASS_TEXT
            setText("07:30")
        }

        val permissionButton = Button(this).apply {
            text = "1. Request permission"
            setOnClickListener {
                requestPermissions()
            }
        }

        val writeButton = Button(this).apply {
            text = "2. Add steps automatically"
            setOnClickListener {
                writeStepsAutomatically()
            }
        }

        val readButton = Button(this).apply {
            text = "3. Refresh selected date total"
            setOnClickListener {
                refreshTotal()
            }
        }

        val recordsButton = Button(this).apply {
            text = "4. View selected date records"
            setOnClickListener {
                fetchSelectedDateStepRecords()
            }
        }

        val deleteButton = Button(this).apply {
            text = "5. Delete selected date app records"
            setOnClickListener {
                deleteMySteps()
            }
        }

        listOf(
            statusView,
            totalView,
            dateInput,
            stepsInput,
            startTimeInput,
            endTimeInput,
            permissionButton,
            writeButton,
            readButton,
            recordsButton,
            deleteButton
        ).forEach {
            root.addView(it)
        }

        return root
    }

    // ---------------------------------------------------------
    // DATE PICKER
    // ---------------------------------------------------------

    private fun showDatePicker() {

        val today = LocalDate.now()
        val zone = ZoneId.systemDefault()

        val datePicker = DatePickerDialog(
            this,
            { _, year, month, day ->

                val chosenDate = LocalDate.of(
                    year,
                    month + 1,
                    day
                )

                // Future-date validation
                if (chosenDate.isAfter(LocalDate.now())) {
                    setStatus("Future dates are not allowed")
                    return@DatePickerDialog
                }

                selectedDate = chosenDate

                dateInput.setText(
                    selectedDate.format(dateFormatter)
                )

                setStatus(
                    "Selected date: ${selectedDate.format(dateFormatter)}"
                )

                // Refresh total for the selected date
                refreshTotal()
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        )

        // Prevent selecting future dates in the calendar
        datePicker.datePicker.maxDate = today
            .plusDays(1)
            .atStartOfDay(zone)
            .toInstant()
            .toEpochMilli() - 1

        datePicker.show()
    }

    // ---------------------------------------------------------
    // HEALTH CONNECT INITIALIZATION
    // ---------------------------------------------------------

    private fun initHealthConnect() {

        when (HealthConnectClient.getSdkStatus(this)) {

            HealthConnectClient.SDK_UNAVAILABLE -> {
                setStatus("Health Connect is not supported on this device")
            }

            HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> {
                setStatus("Please install or update Health Connect")
                openPlayStore()
            }

            else -> {

                client = HealthConnectClient.getOrCreate(this)

                setStatus("Health Connect ready")

                lifecycleScope.launch {
                    try {

                        val granted =
                            client!!.permissionController.getGrantedPermissions()

                        if (granted.containsAll(permissions)) {
                            refreshTotal()
                        } else {
                            setStatus("Tap Request permission first")
                        }

                    } catch (e: Exception) {
                        setStatus("Initialization failed: ${e.message}")
                    }
                }
            }
        }
    }

    private fun requestPermissions() {

        if (client == null) {
            setStatus("Health Connect is not available")
            return
        }

        permissionLauncher.launch(permissions)
    }

    // ---------------------------------------------------------
    // GET SELECTED DATE RECORDS
    // ---------------------------------------------------------

    private fun startOfDate(date: LocalDate): Instant {

        return date.atStartOfDay(ZoneId.systemDefault()).toInstant()
    }

    private fun endOfSelectedDate(date: LocalDate): Instant {

        val endOfDay = date
            .plusDays(1)
            .atStartOfDay(ZoneId.systemDefault())
            .toInstant()

        // For current date, read only up to the current time.
        return minOf(endOfDay, Instant.now())
    }

    private suspend fun getSelectedDateStepRecords(): List<StepsRecord> {

        val hc = client ?: return emptyList()

        val date = selectedDate

        val allRecords = mutableListOf<StepsRecord>()

        var pageToken: String? = null

        do {

            val request = ReadRecordsRequest(
                recordType = StepsRecord::class,
                timeRangeFilter = TimeRangeFilter.between(
                    startOfDate(date),
                    endOfSelectedDate(date)
                ),
                pageSize = 1000,
                pageToken = pageToken
            )

            val response = hc.readRecords(request)

            allRecords.addAll(response.records)

            pageToken = response.pageToken

        } while (pageToken != null)

        return allRecords
    }

    // ---------------------------------------------------------
    // VIEW SELECTED DATE RECORDS
    // ---------------------------------------------------------

    private fun fetchSelectedDateStepRecords() {

        val hc = client ?: return setStatus("Health Connect unavailable")

        lifecycleScope.launch {

            try {

                val records = getSelectedDateStepRecords()

                if (records.isEmpty()) {
                    setStatus("No step records found for selected date")
                    return@launch
                }

                val details = records
                    .sortedBy { it.startTime }
                    .joinToString("\n\n") { record ->

                        val start = record.startTime
                            .atZone(ZoneId.systemDefault())
                            .format(secondsFormatter)

                        val end = record.endTime
                            .atZone(ZoneId.systemDefault())
                            .format(secondsFormatter)

                        """
                        Steps: ${record.count}
                        Time: $start - $end
                        Source: ${record.metadata.dataOrigin.packageName}
                        Recording method: ${record.metadata.recordingMethod}
                        Device: ${record.metadata.device?.manufacturer ?: "Unknown"} ${record.metadata.device?.model ?: ""}
                        """.trimIndent()
                    }

                setStatus(
                    "Records for ${selectedDate.format(dateFormatter)}:\n\n$details"
                )

            } catch (e: Exception) {
                setStatus("Read records failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------
    // CREATE MULTIPLE SESSIONS
    // ---------------------------------------------------------

    private fun createStepSessions(
        start: Instant,
        end: Instant,
        totalSteps: Long
    ): List<Triple<Instant, Instant, Long>> {

        val totalDuration = ChronoUnit.SECONDS.between(start, end)

        if (totalDuration <= 0 || totalSteps <= 0) {
            return emptyList()
        }

        val sessionCount = Random.nextInt(
            5,
            minOf(16, (totalDuration / 45).toInt() + 1).coerceAtLeast(6)
        )

        val sessionDurations = MutableList(sessionCount) {
            Random.nextLong(30, 181)
        }

        val gapDurations = MutableList(sessionCount - 1) {
            Random.nextLong(10, 91)
        }

        val requiredDuration =
            sessionDurations.sum() + gapDurations.sum()

        if (requiredDuration > totalDuration) {

            val scale = totalDuration.toDouble() / requiredDuration.toDouble()

            for (i in sessionDurations.indices) {
                sessionDurations[i] =
                    (sessionDurations[i] * scale).toLong().coerceAtLeast(1)
            }

            for (i in gapDurations.indices) {
                gapDurations[i] =
                    (gapDurations[i] * scale).toLong().coerceAtLeast(0)
            }
        }

        val actualDuration =
            sessionDurations.sum() + gapDurations.sum()

        val remainingDuration =
            (totalDuration - actualDuration).coerceAtLeast(0)

        if (gapDurations.isNotEmpty()) {

            val extraPerGap = remainingDuration / gapDurations.size

            gapDurations.indices.forEach {
                gapDurations[it] += extraPerGap
            }
        }

        val sessions = mutableListOf<Pair<Instant, Instant>>()

        var cursor = start

        for (i in 0 until sessionCount) {

            val sessionStart = cursor
            val sessionEnd = sessionStart.plusSeconds(sessionDurations[i])

            if (sessionEnd.isAfter(end)) break

            sessions.add(sessionStart to sessionEnd)

            cursor = sessionEnd

            if (i < gapDurations.size) {
                cursor = cursor.plusSeconds(gapDurations[i])
            }
        }

        if (sessions.isEmpty()) {
            return listOf(Triple(start, end, totalSteps))
        }

        val weights = sessions.map {
            ChronoUnit.SECONDS.between(it.first, it.second).toDouble()
        }

        val totalWeight = weights.sum()

        var allocated = 0L

        return sessions.mapIndexed { index, session ->

            val count = if (index == sessions.lastIndex) {

                totalSteps - allocated

            } else {

                (totalSteps * weights[index] / totalWeight)
                    .roundToLong()
                    .coerceAtLeast(1)
            }

            allocated += count

            Triple(session.first, session.second, count)
        }
    }

    // ---------------------------------------------------------
    // AUTOMATIC STEP INSERTION
    // ---------------------------------------------------------

    private fun writeStepsAutomatically() {

        val hc = client ?: return setStatus("Health Connect unavailable")

        val requestedSteps = stepsInput.text.toString().toLongOrNull()

        if (requestedSteps == null || requestedSteps <= 0) {
            setStatus("Enter a valid step count")
            return
        }

        // Validate selected date
        val date = selectedDate
        val today = LocalDate.now()

        if (date.isAfter(today)) {
            setStatus("Future dates are not allowed")
            return
        }

        val startTime: LocalTime
        val endTime: LocalTime

        try {

            startTime = LocalTime.parse(
                startTimeInput.text.toString().trim(),
                timeFormatter
            )

            endTime = LocalTime.parse(
                endTimeInput.text.toString().trim(),
                timeFormatter
            )

        } catch (e: Exception) {

            setStatus("Enter time in HH:mm format, e.g. 07:00")
            return
        }

        if (!endTime.isAfter(startTime)) {
            setStatus("End time must be after start time")
            return
        }

        val zone = ZoneId.systemDefault()

        val requestedStart = date
            .atTime(startTime)
            .atZone(zone)
            .toInstant()

        val requestedEnd = date
            .atTime(endTime)
            .atZone(zone)
            .toInstant()

        // Future-time validation for current date
        if (requestedEnd.isAfter(Instant.now())) {
            setStatus("End time cannot be in the future")
            return
        }

        lifecycleScope.launch {

            try {

                val granted = hc.permissionController.getGrantedPermissions()

                if (!granted.containsAll(permissions)) {
                    setStatus("Please grant Health Connect permissions first")
                    return@launch
                }

                setStatus("Checking existing records...")

                val existingRecords = getSelectedDateStepRecords()

                val relevantRecords = existingRecords
                    .filter { record ->
                        record.startTime.isBefore(requestedEnd) &&
                            record.endTime.isAfter(requestedStart)
                    }
                    .sortedBy { it.startTime }

                // Calculate existing steps in the requested interval
                var existingSteps = 0L

                for (record in relevantRecords) {

                    val overlapStart = maxOf(
                        record.startTime,
                        requestedStart
                    )

                    val overlapEnd = minOf(
                        record.endTime,
                        requestedEnd
                    )

                    val overlapDuration = ChronoUnit.MILLIS.between(
                        overlapStart,
                        overlapEnd
                    )

                    val recordDuration = ChronoUnit.MILLIS.between(
                        record.startTime,
                        record.endTime
                    )

                    if (recordDuration <= 0 || overlapDuration <= 0) {
                        continue
                    }

                    val stepsInOverlap = (
                        record.count.toDouble() *
                            overlapDuration.toDouble() /
                            recordDuration.toDouble()
                        ).roundToLong()

                    existingSteps += stepsInOverlap
                }

                val remainingSteps = requestedSteps - existingSteps

                if (remainingSteps <= 0) {

                    setStatus(
                        """
                        No new records created.

                        Selected date: $date
                        Requested steps: $requestedSteps
                        Existing steps: $existingSteps

                        Existing steps already meet or exceed your target.
                        """.trimIndent()
                    )

                    refreshTotal()
                    return@launch
                }

                // Find uncovered intervals
                val gaps = findUncoveredIntervals(
                    requestedStart,
                    requestedEnd,
                    relevantRecords
                )

                if (gaps.isEmpty()) {
                    setStatus("No uncovered time interval available")
                    return@launch
                }

                val totalGapDuration = gaps.sumOf {
                    ChronoUnit.MILLIS.between(it.first, it.second)
                }

                if (totalGapDuration <= 0) {
                    setStatus("No valid time interval available")
                    return@launch
                }

                // Distribute remaining steps between uncovered intervals
                val intervalAllocations =
                    mutableListOf<Pair<Pair<Instant, Instant>, Long>>()

                var allocatedSteps = 0L

                gaps.forEachIndexed { index, gap ->

                    val duration = ChronoUnit.MILLIS.between(
                        gap.first,
                        gap.second
                    )

                    val stepsForGap = if (index == gaps.lastIndex) {

                        remainingSteps - allocatedSteps

                    } else {

                        (
                            remainingSteps.toDouble() *
                                duration.toDouble() /
                                totalGapDuration.toDouble()
                            ).roundToLong()
                    }

                    allocatedSteps += stepsForGap

                    intervalAllocations.add(gap to stepsForGap)
                }

                val device = Device(
                    manufacturer = Build.MANUFACTURER,
                    model = Build.MODEL,
                    type = Device.TYPE_PHONE
                )

                val recordsToInsert = mutableListOf<StepsRecord>()

                // Break uncovered intervals into smaller sessions
                for ((interval, intervalSteps) in intervalAllocations) {

                    if (intervalSteps <= 0) continue

                    val sessions = createStepSessions(
                        interval.first,
                        interval.second,
                        intervalSteps
                    )

                    for ((sessionStart, sessionEnd, sessionSteps) in sessions) {

                        if (sessionSteps <= 0) continue

                        recordsToInsert.add(
                            StepsRecord(
                                count = sessionSteps,
                                startTime = sessionStart,
                                startZoneOffset = zone.rules.getOffset(sessionStart),
                                endTime = sessionEnd,
                                endZoneOffset = zone.rules.getOffset(sessionEnd),
                                metadata = Metadata.activelyRecorded(device)
                            )
                        )
                    }
                }

                if (recordsToInsert.isEmpty()) {
                    setStatus("No new step records to insert")
                    return@launch
                }

                hc.insertRecords(recordsToInsert)

                val insertedSteps = recordsToInsert.sumOf { it.count }

                val result = buildString {

                    appendLine("Step sessions created successfully!")
                    appendLine()

                    appendLine("Selected date: $date")
                    appendLine("Requested total: $requestedSteps")
                    appendLine("Existing steps: $existingSteps")
                    appendLine("New steps added: $insertedSteps")
                    appendLine("Sessions created: ${recordsToInsert.size}")

                    appendLine()
                    appendLine("New sessions:")

                    recordsToInsert.sortedBy { it.startTime }.forEach { record ->

                        val start = record.startTime
                            .atZone(zone)
                            .format(secondsFormatter)

                        val end = record.endTime
                            .atZone(zone)
                            .format(secondsFormatter)

                        appendLine("$start - $end = ${record.count} steps")
                    }

                    appendLine()

                    appendLine(
                        "Total estimated steps: ${existingSteps + insertedSteps}"
                    )

                    appendLine()
                    appendLine("Recording method: 2 (Manual entry)")
                }

                setStatus(result)

                refreshTotal()

            } catch (e: Exception) {
                setStatus("Write failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------
    // FIND UNCOVERED TIME INTERVALS
    // ---------------------------------------------------------

    private fun findUncoveredIntervals(
        requestedStart: Instant,
        requestedEnd: Instant,
        records: List<StepsRecord>
    ): List<Pair<Instant, Instant>> {

        val gaps = mutableListOf<Pair<Instant, Instant>>()

        val intervals = records.mapNotNull { record ->

            val start = maxOf(record.startTime, requestedStart)
            val end = minOf(record.endTime, requestedEnd)

            if (start.isBefore(end)) {
                start to end
            } else {
                null
            }

        }.sortedBy { it.first }

        var currentPosition = requestedStart

        for (interval in intervals) {

            val existingStart = interval.first
            val existingEnd = interval.second

            if (existingStart.isAfter(currentPosition)) {
                gaps.add(currentPosition to existingStart)
            }

            if (existingEnd.isAfter(currentPosition)) {
                currentPosition = existingEnd
            }
        }

        if (currentPosition.isBefore(requestedEnd)) {
            gaps.add(currentPosition to requestedEnd)
        }

        return gaps
    }

    // ---------------------------------------------------------
    // REFRESH SELECTED DATE TOTAL
    // ---------------------------------------------------------

    private fun refreshTotal() {

        val hc = client ?: return

        val date = selectedDate

        if (date.isAfter(LocalDate.now())) {
            setStatus("Future dates are not allowed")
            return
        }

        lifecycleScope.launch {

            try {

                val response = hc.aggregate(
                    AggregateRequest(
                        metrics = setOf(StepsRecord.COUNT_TOTAL),
                        timeRangeFilter = TimeRangeFilter.between(
                            startOfDate(date),
                            endOfSelectedDate(date)
                        )
                    )
                )

                val total = response[StepsRecord.COUNT_TOTAL] ?: 0L

                totalView.text =
                    "Steps on ${date.format(dateFormatter)}: $total"

            } catch (e: Exception) {
                setStatus("Total read failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------
    // DELETE SELECTED DATE APP RECORDS ONLY
    // ---------------------------------------------------------

    private fun deleteMySteps() {

        val hc = client ?: return setStatus("Health Connect unavailable")

        val date = selectedDate

        if (date.isAfter(LocalDate.now())) {
            setStatus("Future dates are not allowed")
            return
        }

        lifecycleScope.launch {

            try {

                val records = getSelectedDateStepRecords()

                val myRecords = records.filter {
                    it.metadata.dataOrigin.packageName == packageName
                }

                if (myRecords.isEmpty()) {
                    setStatus("No records written by this app on selected date")
                    return@launch
                }

                hc.deleteRecords(
                    recordType = StepsRecord::class,
                    recordIdsList = myRecords.map { it.metadata.id },
                    clientRecordIdsList = emptyList()
                )

                setStatus(
                    "Deleted ${myRecords.size} records written by this app on $date"
                )

                refreshTotal()

            } catch (e: Exception) {
                setStatus("Delete failed: ${e.message}")
            }
        }
    }

    // ---------------------------------------------------------
    // HELPERS
    // ---------------------------------------------------------

    private fun setStatus(message: String) {
        statusView.text = message
    }

    private fun openPlayStore() {

        val uri =
            "market://details?id=com.google.android.apps.healthdata" +
                "&url=healthconnect%3A%2F%2Fonboarding"

        startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setPackage("com.android.vending")
                data = Uri.parse(uri)
                putExtra("overlay", true)
                putExtra("callerId", packageName)
            }
        )
    }
}
