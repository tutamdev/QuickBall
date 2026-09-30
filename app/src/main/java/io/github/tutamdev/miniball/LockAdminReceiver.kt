package io.github.tutamdev.miniball

import android.app.admin.DeviceAdminReceiver

/** Device admin that declares only <force-lock/>. It cannot wipe, reset passwords or read data. */
class LockAdminReceiver : DeviceAdminReceiver()
