package com.ronimizy.contacts

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.core.content.ContextCompat
import androidx.core.database.getStringOrNull
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.ronimizy.contacts.ui.theme.ContactsTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val viewModel: ContactsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ContactsTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->

                    ContactsPage(viewModel, Modifier.padding(innerPadding))

                }
            }
        }
    }
}

data class Contact(val name: String, val phoneNumber: String?, val email: String?)

sealed interface ContactResolutionResult {
    data object InProgress : ContactResolutionResult
    data object NoPermission : ContactResolutionResult
    data object NotFound : ContactResolutionResult
    data class Found(val contacts: List<Contact>) : ContactResolutionResult
}

class ContactsViewModel(app: Application) : AndroidViewModel(app) {
    private val _state =
        MutableStateFlow<ContactResolutionResult>(ContactResolutionResult.InProgress)
    val state: StateFlow<ContactResolutionResult> = _state.asStateFlow()


    init {
        tryReloadContacts()
    }

    fun tryReloadContacts() {
        onHasPermissionChanged(hasContactsPermission())
    }

    fun onHasPermissionChanged(hasPermission: Boolean) {
        if (!hasPermission) {
            _state.value = ContactResolutionResult.NoPermission
            return
        }

        if (_state.value !is ContactResolutionResult.NoPermission
            && _state.value !is ContactResolutionResult.InProgress
        ) {
            return
        }

        _state.value = ContactResolutionResult.InProgress

        viewModelScope.launch {
            val contacts = getApplication<Application>().fetchAllContacts()

            _state.value = when {
                contacts.isEmpty() -> ContactResolutionResult.NotFound
                else -> ContactResolutionResult.Found(contacts)
            }
        }
    }

    private fun hasContactsPermission(): Boolean {
        val permission = ContextCompat.checkSelfPermission(
            getApplication(), Manifest.permission.READ_CONTACTS
        )

        return permission == PackageManager.PERMISSION_GRANTED
    }
}

@SuppressLint("Range")
fun Context.fetchAllContacts(): List<Contact> {
    Log.d("FETCH", "fetchAllContacts called")
    contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        null,
        null,
        null,
        null
    )
        .use { cursor: Cursor? ->
            if (cursor == null) return emptyList()
            return buildList {
                while (cursor.moveToNext()) {
                    val name =
                        cursor.getStringOrNull(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME))
                    val phoneNumber =
                        cursor.getStringOrNull(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER))
                    val email =
                        cursor.getStringOrNull(cursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS))

                    if (name == null)
                        continue

                    add(Contact(name, phoneNumber, email))
                }
            }
        }
}

fun Context.dial(number: String) {
    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${Uri.encode(number)}"))
    try {
        startActivity(intent)
    } catch (e: Exception) {
    }
}

@Composable
fun ContactsPage(viewModel: ContactsViewModel, modifier: Modifier) {
    var requestedOnce by rememberSaveable { mutableStateOf(false) }

    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        requestedOnce = true
        viewModel.onHasPermissionChanged(granted)
    }

    LaunchedEffect(Unit) {
        if (viewModel.state.value is ContactResolutionResult.NoPermission) {
            launcher.launch(Manifest.permission.READ_CONTACTS)
        }
    }

    LifecycleResumeEffect(Unit) {
        viewModel.tryReloadContacts()
        onPauseOrDispose { }
    }

    Column(modifier) {
        Column(
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
        ) {
            when (val result = viewModel.state.collectAsState().value) {
                ContactResolutionResult.NoPermission -> {
                    Text(stringResource(R.string.contacts_forbidden))
                }

                ContactResolutionResult.NotFound -> {
                    Text(stringResource(R.string.contacts_not_found))
                }

                is ContactResolutionResult.Found -> {
                    Column(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(24.dp)
                    ) {

                        Column {
                            Text(
                                stringResource(R.string.app_name),
                                style = MaterialTheme.typography.headlineMedium
                            )
                            Text(stringResource(R.string.found_contacts_template).format(result.contacts.size))
                        }

                        LazyColumn(
                            Modifier.fillMaxWidth(),
                            userScrollEnabled = true,
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(result.contacts, key = { it.name }) { contact ->
                                ContactComponent(contact)
                            }
                        }

                    }
                }

                else -> {

                }
            }
        }
    }

}

@Composable
fun ContactComponent(contact: Contact) {
    val context = LocalContext.current

    Column(
        Modifier
            .background(Color.LightGray, shape = RoundedCornerShape(12.dp))
            .fillMaxWidth()
            .padding(8.dp)
            .clickable {
                contact.phoneNumber?.let { context.dial(it) }
            }) {
        Text(contact.name)

        if (contact.phoneNumber != null) {
            Text("Phone: ${contact.phoneNumber}")
        }

        if (contact.email != null) {
            Text("Email: ${contact.email}")
        }
    }
}