/*
 * Copyright 2022-2023 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.openmrs.android.fhir.viewmodel

import IdentifierTypeManager
import android.app.Application
import android.content.Context
import android.text.format.DateFormat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import com.google.android.fhir.search.search
import com.google.android.fhir.sync.CurrentSyncJobStatus
import com.google.android.fhir.sync.PeriodicSyncConfiguration
import com.google.android.fhir.sync.PeriodicSyncJobStatus
import com.google.android.fhir.sync.RepeatInterval
import com.google.android.fhir.sync.Sync
import kotlinx.coroutines.Dispatchers
import org.openmrs.android.fhir.data.FhirSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import org.json.JSONObject
import org.openmrs.android.fhir.FhirApplication
import org.openmrs.android.fhir.auth.dataStore
import org.openmrs.android.fhir.data.PreferenceKeys
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/** View model for [MainActivity]. */
class MainActivityViewModel(application: Application) : AndroidViewModel(application) {
  private val _lastSyncTimestampLiveData = MutableLiveData<String>()
  val lastSyncTimestampLiveData: LiveData<String>
    get() = _lastSyncTimestampLiveData

  private val _oneTimeSyncTrigger = MutableStateFlow(false)

  val pollPeriodicSyncJobStatus: SharedFlow<PeriodicSyncJobStatus> =
    Sync.periodicSync<FhirSyncWorker>(
      application.applicationContext,
      periodicSyncConfiguration =
      PeriodicSyncConfiguration(
        syncConstraints = Constraints.Builder().build(),
        repeat = RepeatInterval(interval = 15, timeUnit = TimeUnit.MINUTES),
      ),
    )
      .shareIn(viewModelScope, SharingStarted.Eagerly, 10)

  val pollState: SharedFlow<CurrentSyncJobStatus> =
    _oneTimeSyncTrigger
      .combine(
        flow = Sync.oneTimeSync<FhirSyncWorker>(context = application.applicationContext),
      ) { _, syncJobStatus ->
        syncJobStatus
      }
      .shareIn(viewModelScope, SharingStarted.Eagerly, 0)

  fun triggerOneTimeSync(context: Context) {
    viewModelScope.launch {
      fetchIdentifierTypesIfEmpty(context)
      embeddIdentifierToUnsyncedPatients(context)
      _oneTimeSyncTrigger.value = !_oneTimeSyncTrigger.value

      _oneTimeSyncTrigger
        .combine(
          flow = Sync.oneTimeSync<FhirSyncWorker>(context = getApplication<Application>().applicationContext),
        ) { _, syncJobStatus ->
          syncJobStatus
        }
    }
  }

  fun triggerIdentifierTypeSync(context: Context) {
    viewModelScope.launch {
      fetchIdentifierTypesIfEmpty(context)
    }
  }

  private suspend fun embeddIdentifierToUnsyncedPatients(context: Context) {
    //Setting location session first.
    context.dataStore.data.first()[PreferenceKeys.LOCATION_ID]?.let {
      FhirApplication.restApiClient(getApplication<FhirApplication>().applicationContext).updateSessionLocation(
        it
      )
    }
    var filteredIdentifierTypes = setOf<String>()
    val identifierTypeToSourceIdMap = mutableMapOf<String,String>()
    val selectedIdentifierTypes = context.dataStore.data.first()[PreferenceKeys.SELECTED_IDENTIFIER_TYPES]?.toList()
    if(selectedIdentifierTypes != null) {
       filteredIdentifierTypes = selectedIdentifierTypes.filter { identifierTypeId ->
         val identifierType = let {
           FhirApplication.roomDatabase(context).dao()
             .getIdentifierTypeById(identifierTypeId)
         }
         val isAutoGenerated = identifierType?.isAutoGenerated
         if(isAutoGenerated == true){
           identifierTypeToSourceIdMap[identifierTypeId] = identifierType.sourceId
         }
         isAutoGenerated != null && isAutoGenerated == true
      }.toSet()
    }

    val patients = FhirApplication.fhirEngine(context).search<org.hl7.fhir.r4.model.Patient> {
      filter(org.hl7.fhir.r4.model.Patient.IDENTIFIER, {
        value=of("unsynced")
      })
    }.map { it.resource }.toMutableList()


    patients.forEach {
      val identifiers = it.identifier
      identifiers.forEach { identifier ->
        if(identifier.value in filteredIdentifierTypes) {
          val value = identifierTypeToSourceIdMap[identifier.value]?.let { it1 ->
            fetchIdentifierFromEndpoint(
              it1
            )
          }
          identifier.value = value
        }
      }
      identifiers.removeAt(0)
      it.identifier = identifiers
      FhirApplication.fhirEngine(getApplication<Application>().applicationContext).update(it)
    }
  }

  private suspend fun fetchIdentifierFromEndpoint(identifierId: String): String? {
    return withContext(Dispatchers.IO) {
      try {

        val requestBuilder = Request.Builder()
          .url(getEndpoint(identifierId))
          .post(RequestBody.create("application/json; charset=utf-8".toMediaType(), "{}"))
        val response = FhirApplication.restApiClient(getApplication<FhirApplication>().applicationContext).call(requestBuilder)

        if (response.isSuccessful) {
          val jsonResponse = JSONObject(response.body?.string() ?: "")
          jsonResponse.getString("identifier")
        } else {
          null
        }
      } catch (e: Exception) {
        null
      }
    }
  }

  private fun getEndpoint(idType: String) : String{
    return FhirApplication.openmrsRestUrl(getApplication<Application>().applicationContext) + "idgen/identifiersource/" + idType + "/identifier"
  }

  private suspend fun fetchIdentifierTypesIfEmpty(context: Context) {
      val identifierTypes = FhirApplication.roomDatabase(context).dao().getIdentifierTypesCount()
      if(identifierTypes == 0) {
        IdentifierTypeManager.fetchIdentifiers(context)
      }
  }

  /** Emits last sync time. */
  fun updateLastSyncTimestamp(lastSync: OffsetDateTime? = null) {
    val formatter =
      DateTimeFormatter.ofPattern(
        if (DateFormat.is24HourFormat(getApplication())) formatString24 else formatString12,
      )
    _lastSyncTimestampLiveData.value =
      lastSync?.let { it.toLocalDateTime()?.format(formatter) ?: "" }
        ?: Sync.getLastSyncTimestamp(getApplication())?.toLocalDateTime()?.format(formatter) ?: ""
  }

  companion object {
    private const val formatString24 = "yyyy-MM-dd HH:mm:ss"
    private const val formatString12 = "yyyy-MM-dd hh:mm:ss a"
  }
}
