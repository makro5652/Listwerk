package app.listwerk.android

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.listwerk.android.data.ListwerkDatabase
import app.listwerk.android.data.RoomBackend
import app.listwerk.core.StoreBackend
import app.listwerk.core.StoreContractTest
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** Dieselben Verhaltensprüfungen wie der JVM-Test von :core, aber gegen die echte Room-Datenbank (Robolectric, Datei auf der Platte). */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RoomStoreTest : StoreContractTest() {
    private val context: Context get() = ApplicationProvider.getApplicationContext()
    private val name = "contract-${System.nanoTime()}.db"

    private fun open(): ListwerkDatabase =
        Room.databaseBuilder(context, ListwerkDatabase::class.java, name).allowMainThreadQueries().build()

    override fun newBackend(): StoreBackend = RoomBackend(open())

    override fun reopen(previous: StoreBackend): StoreBackend? = RoomBackend(open())
}
