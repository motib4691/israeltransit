package il.co.israeltransit.offline.maps

import androidx.compose.ui.res.stringResource
import il.co.israeltransit.offline.R

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import il.co.israeltransit.offline.database.TransitRepository
import il.co.israeltransit.offline.database.TransitStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

@Composable
fun OfflineMapImportScreen(
    onBack: () -> Unit,
    onPlanRouteFrom: ((TransitRepository.TransitStop) -> Unit)? = null,
    onPlanRouteTo: ((TransitRepository.TransitStop) -> Unit)? = null
) {
    val context = LocalContext.current

    val mapStorage = remember(context) {
        OfflineMapStorage(context)
    }

    val transitStorage = remember(context) {
        TransitStorage(context)
    }

    val scope = rememberCoroutineScope()

    var busy by remember {
        mutableStateOf(false)
    }

    var refreshVersion by remember {
        mutableStateOf(0)
    }

    var statusMessage by remember {
        mutableStateOf(
            "בחר קובץ PMTiles מהמכשיר כדי להתקין את המפה."
        )
    }

    var succeeded by remember {
        mutableStateOf(false)
    }

    val unavailableStorage =
        transitStorage.hasUnavailableSelectedLocation()

    val mapFile: File = remember(refreshVersion) {
        mapStorage.getMapFile()
    }

    val mapAvailable = remember(refreshVersion) {
        mapStorage.isMapAvailable()
    }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && !busy) {
            scope.launch {
                busy = true
                succeeded = false

                statusMessage =
                    stringResource(R.string.ui_e9ff06)

                val result = withContext(Dispatchers.IO) {
                    mapStorage.importArchive(uri)
                }

                busy = false
                succeeded = result.success
                statusMessage = result.message
                refreshVersion++
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = stringResource(R.string.ui_1fa750),
            style = MaterialTheme.typography.headlineMedium
        )

        Text(
            text = "המפה נשמרת באותו מיקום אחסון שנבחר עבור מאגר התחבורה. " +
                stringResource(R.string.ui_25e18f)
        )

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = stringResource(R.string.ui_46e08a),
                    style = MaterialTheme.typography.titleMedium
                )

                if (unavailableStorage) {
                    Text(
                        text = "מיקום האחסון שנבחר אינו זמין. " +
                            stringResource(R.string.ui_93d994),
                        color = MaterialTheme.colorScheme.error
                    )
                } else if (mapAvailable) {
                    Text(stringResource(R.string.ui_b1973c))
                    Text(
                        text = "גודל הקובץ: " +
                            formatMapSize(mapFile.length())
                    )
                } else {
                    Text(stringResource(R.string.ui_b62ba2))
                }

                Button(
                    onClick = {
                        filePicker.launch(
                            arrayOf(
                                "application/octet-stream",
                                "application/x-pmtiles",
                                "*/*"
                            )
                        )
                    },
                    enabled = !busy && !unavailableStorage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(stringResource(R.string.ui_9ecf70))
                }
            }
        }

        if (busy) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(R.string.ui_d15fd9),
                    modifier = Modifier.padding(8.dp)
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = statusMessage,
                modifier = Modifier.padding(14.dp),
                color = if (succeeded) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurface
                }
            )
        }

        if (mapAvailable && !unavailableStorage && !busy) {
            Text(
                text = stringResource(R.string.ui_4dc4c7),
                style = MaterialTheme.typography.titleMedium
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                TransitMapScreen(
                    pmtilesFile = mapFile,
                    modifier = Modifier.fillMaxSize(),
                    onPlanRouteFrom = onPlanRouteFrom,
                    onPlanRouteTo = onPlanRouteTo,
                    onError = { error ->
                        statusMessage = error
                        succeeded = false
                    }
                )
            }
        }

        OutlinedButton(
            onClick = onBack,
            enabled = !busy,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.ui_b95e2a))
        }
    }
}

private fun formatMapSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return String.format(
        java.util.Locale.getDefault(),
        "%.1f MB",
        mb
    )
}