package app.listwerk.core

class InMemoryStoreTest : StoreContractTest() {
    override fun newBackend(): StoreBackend = InMemoryBackend()
}
