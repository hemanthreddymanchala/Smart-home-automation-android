package com.example.shas

import android.app.Application
import com.amazonaws.auth.CognitoCachingCredentialsProvider
import com.amazonaws.mobile.client.AWSMobileClient
import com.amazonaws.regions.Regions
import com.kevin.devil.Devil
import com.kevin.devil.models.DevilConfig
import timber.log.Timber

class App : Application() {

    private val serverUri = "tcp://142.93.57.145:1883"

    override fun onCreate() {
        super.onCreate()

        val credentialsProvider: CognitoCachingCredentialsProvider =
            CognitoCachingCredentialsProvider(
                applicationContext,
                "us-east-2:5f4f5852-cc94-435c-848e-fc12dddbf9cb",  // Identity pool ID
                Regions.US_EAST_2 // Region
            )

        AWSMobileClient.getInstance().credentialsProvider = credentialsProvider

        if (BuildConfig.DEBUG) {
            Timber.plant(object : Timber.DebugTree() {
                override fun createStackElementTag(element: StackTraceElement): String {
                    return "(${element.fileName}:${element.lineNumber})#${element.methodName}"
                }
            })
        }

//        Devil.breath(
//            DevilConfig(
//                true,
//                true,
//                serverUri,
//                applicationContext,
//                "Tag",
//                "Will_topic",
//                "123456"
//            )
//        )
    }
}