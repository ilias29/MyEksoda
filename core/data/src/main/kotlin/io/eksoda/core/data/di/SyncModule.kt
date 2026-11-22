package io.eksoda.core.data.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import io.eksoda.core.data.repository.SyncRepository
import io.eksoda.core.data.repository.SyncRepositoryImpl
import io.eksoda.core.data.sync.KeystoreSyncDevice
import io.eksoda.core.data.sync.PeerDiscovery
import io.eksoda.core.data.sync.SyncControl
import io.eksoda.core.data.sync.SyncDevice
import io.eksoda.core.data.sync.SyncDiscovery
import io.eksoda.core.data.sync.SyncRuntime

/** Household sync (docs/design/household-sync.md). */
@Module
@InstallIn(SingletonComponent::class)
interface SyncModule {
    @Binds
    fun bindSyncRepository(implementation: SyncRepositoryImpl): SyncRepository

    @Binds
    fun bindSyncDevice(implementation: KeystoreSyncDevice): SyncDevice

    @Binds
    fun bindSyncControl(implementation: SyncRuntime): SyncControl

    @Binds
    fun bindPeerDiscovery(implementation: SyncDiscovery): PeerDiscovery
}
