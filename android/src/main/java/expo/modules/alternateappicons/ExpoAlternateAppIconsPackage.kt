package expo.modules.alternateappicons

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import expo.modules.core.interfaces.ApplicationLifecycleListener
import expo.modules.core.interfaces.Package
import expo.modules.core.interfaces.ReactActivityLifecycleListener

class ExpoAlternateAppIconsPackage : Package {
  override fun createApplicationLifecycleListeners(context: Context): List<ApplicationLifecycleListener> {
    return listOf(object : ApplicationLifecycleListener {
      override fun onCreate(application: Application) {
        restoreMainActivity(application)
      }
    })
  }

  override fun createReactActivityLifecycleListeners(activityContext: Context): List<ReactActivityLifecycleListener> {
    return listOf(object : ReactActivityLifecycleListener {
      override fun onCreate(activity: Activity, savedInstanceState: Bundle?) {
        hideDefaultAliasIfAlternatePicked(activity)
      }
    })
  }
}

/**
 * Versions before the Default alias switched icons by disabling MainActivity itself,
 * and Android keeps a component's enabled state across app updates. Such an install
 * keeps MainActivity disabled after updating: deep links do not resolve, and AppCompat,
 * which cannot find a disabled activity's manifest entry, assumes it does not handle
 * `uiMode` and recreates it on every night-mode change. MainActivity no longer has a
 * launcher entry, so enabling it adds no icon and closes no task. This runs in
 * Application.onCreate, before the first activity reads its manifest entry.
 */
internal fun restoreMainActivity(context: Context) {
  val pm = context.packageManager
  val packageName = context.packageName

  try {
    val packageInfo = pm.getPackageInfo(
      packageName,
      PackageManager.GET_ACTIVITIES or PackageManager.GET_DISABLED_COMPONENTS
    )
    val mainActivity = packageInfo.activities?.firstOrNull {
      it.targetActivity == null && it.name.split('.').last() == MAIN_ACTIVITY_NAME
    } ?: return
    val componentName = ComponentName(packageName, mainActivity.name)

    if (pm.getComponentEnabledSetting(componentName) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED) {
      pm.setComponentEnabledSetting(
        componentName,
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT,
        PackageManager.DONT_KILL_APP
      )
    }
  } catch (e: Exception) {
    e.printStackTrace()
  }
}

/**
 * The Default alias is new and enabled by the manifest, so an install that picked an
 * alternate icon before it existed shows two launcher icons after updating: the picked
 * one (enabled explicitly) and Default. An explicitly enabled alternate means the user
 * picked it, so Default is switched off — the same state setAlternateAppIcon leaves.
 * Never while the activity was started through Default: disabling the component a task
 * was started from finishes that task, so that launch keeps both until the next one.
 */
internal fun hideDefaultAliasIfAlternatePicked(activity: Activity) {
  val pm = activity.packageManager
  val packageName = activity.packageName
  val launchedFrom = activity.intent?.component?.className?.split('.')?.last()
  if (launchedFrom == DEFAULT_ALIAS_NAME) {
    return
  }

  try {
    val aliases = pm.getPackageInfo(
      packageName,
      PackageManager.GET_ACTIVITIES or PackageManager.GET_DISABLED_COMPONENTS
    ).activities.orEmpty().filter { it.targetActivity != null }
    val defaultAlias = aliases.firstOrNull { it.name.split('.').last() == DEFAULT_ALIAS_NAME } ?: return
    val defaultComponent = ComponentName(packageName, defaultAlias.name)
    if (pm.getComponentEnabledSetting(defaultComponent) != PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
      return
    }
    val alternatePicked = aliases.any {
      it.name != defaultAlias.name &&
        pm.getComponentEnabledSetting(ComponentName(packageName, it.name)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
    }
    if (alternatePicked) {
      pm.setComponentEnabledSetting(
        defaultComponent,
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
        PackageManager.DONT_KILL_APP
      )
    }
  } catch (e: Exception) {
    e.printStackTrace()
  }
}
