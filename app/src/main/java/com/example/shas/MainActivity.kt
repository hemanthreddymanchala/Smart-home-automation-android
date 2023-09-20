package com.example.shas

import android.annotation.SuppressLint
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.speech.RecognizerIntent
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.amazonaws.mobile.client.AWSMobileClient
import com.amazonaws.mobileconnectors.s3.transferutility.TransferListener
import com.amazonaws.mobileconnectors.s3.transferutility.TransferState
import com.amazonaws.mobileconnectors.s3.transferutility.TransferUtility
import com.amazonaws.services.s3.AmazonS3Client
import com.amazonaws.services.s3.model.CannedAccessControlList
import com.example.shas.databinding.ActivityMainBinding
import com.example.shas.mqtt.MQTTClient
import com.example.shas.mqtt.MQTT_SERVER_URI
import com.example.shas.util.Api
import com.example.shas.util.RetrofitInstance
import com.google.api.gax.core.FixedCredentialsProvider
import com.google.auth.oauth2.GoogleCredentials
import com.google.cloud.speech.v1.*
import com.google.protobuf.ByteString
import org.apache.commons.io.FileUtils
import org.eclipse.paho.client.mqttv3.*
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import timber.log.Timber
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.*


class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    lateinit var recorder: MediaRecorder
    private lateinit var output: String
    private lateinit var name: String
    lateinit var api: Api


    @RequiresApi(Build.VERSION_CODES.O)
    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.anim.setAnimation("record.json")

        api = RetrofitInstance.getRetrofitInstance().create(Api::class.java)

        initMqtt()

        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            val permissions = arrayOf(
                android.Manifest.permission.RECORD_AUDIO,
                android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            )
            ActivityCompat.requestPermissions(this, permissions, 0)
        }




        binding.record.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (ContextCompat.checkSelfPermission(
                            this,
                            android.Manifest.permission.RECORD_AUDIO
                        ) != PackageManager.PERMISSION_GRANTED && ContextCompat.checkSelfPermission(
                            this,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE
                        ) != PackageManager.PERMISSION_GRANTED
                    ) {
                        val permissions = arrayOf(
                            android.Manifest.permission.RECORD_AUDIO,
                            android.Manifest.permission.WRITE_EXTERNAL_STORAGE,
                            android.Manifest.permission.READ_EXTERNAL_STORAGE
                        )
                        ActivityCompat.requestPermissions(this, permissions, 0)
                    } else {
                        startRecording()
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    stopRecording()
                }
            }
            false
        }

        binding.play.setOnClickListener {
            try {
                val mp = MediaPlayer()
                mp.setDataSource(output)
                mp.prepare()
                mp.start()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun stopRecording() {
        try {
            binding.play.visibility = View.VISIBLE
            binding.anim.setAnimation("record.json")
//            binding.record.playAnimation()
            binding.anim.pauseAnimation()
            recorder.stop()
            recorder.reset()
            recorder.release()
            Timber.e("Stop recording")
            Timber.e(output)
//            uploadImage(File(output), name)

        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
    }

    private fun startRecording() {
        try {
            binding.anim.setAnimation("music.json")
            binding.anim.playAnimation()
            name = "recording.amr"
            val cw = ContextWrapper(this)
            val fullPath = cw.getExternalFilesDir(Environment.DIRECTORY_MUSIC).toString()
            val directory: File? = cw.getExternalFilesDir(Environment.DIRECTORY_MUSIC)
            output = "$fullPath/$name"
            recorder = MediaRecorder()

            recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            recorder.setOutputFormat(MediaRecorder.OutputFormat.AMR_WB)
            recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AMR_WB)
            recorder.setAudioSamplingRate(16000)
            recorder.setOutputFile(output)
            recorder.prepare()
            recorder.start()
            Timber.e("Start recording")
        } catch (e: IllegalStateException) {
            e.printStackTrace()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }


    var image = "";
    private fun uploadImage(part: File, name: String) {

        val transferUtility = TransferUtility.builder()
            .context(this)
            .defaultBucket("ocsimagebucket")
            .awsConfiguration(AWSMobileClient.getInstance().configuration)
            .s3Client(AmazonS3Client(AWSMobileClient.getInstance().credentialsProvider))
            .build()
        val filename = System.currentTimeMillis().toString() + name
        val ext = part.absolutePath.substring(part.absolutePath.lastIndexOf("."))
        val uploadObserver =
            transferUtility.upload(filename, part, CannedAccessControlList.PublicRead)
        uploadObserver.setTransferListener(object : TransferListener {
            @RequiresApi(Build.VERSION_CODES.O)
            override fun onStateChanged(id: Int, state: TransferState) {
                if (TransferState.COMPLETED == state) {
                    // Handle a completed upload.
                    image = "https://ocsimagebucket.s3.amazonaws.com/$filename"
                    Log.e("upload file", image)
                    speechToText()
                }
            }

            override fun onProgressChanged(id: Int, bytesCurrent: Long, bytesTotal: Long) {
                try {
                    Log.e("percentage", (bytesTotal / bytesCurrent).toString() + "  %")
                } catch (exception: Exception) {
                    exception.printStackTrace()
                }
            }

            override fun onError(id: Int, ex: Exception) {
                // Handle errors
                ex.printStackTrace()
            }
        })
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 9) {
            if (resultCode == RESULT_OK && data != null) {
                Timber.e(data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).toString())
            }
        }
    }

    @RequiresApi(Build.VERSION_CODES.O)
    private fun speechToText() {
        val inputStream = resources.openRawResource(R.raw.config)
        val credentials = GoogleCredentials.fromStream(inputStream)
        val speechClient = SpeechClient.create(
            SpeechSettings.newBuilder()
                .setCredentialsProvider(FixedCredentialsProvider.create(credentials)).build()
        )


        // Instantiates a client
        // Instantiates a client
//        val speech: SpeechClient = SpeechClient.create()

        // The path to the audio file to transcribe

        // The path to the audio file to transcribe
        val fileName = output

        // Reads the audio file into memory

        // Reads the audio file into memory
        val path: Path = Paths.get(fileName)
        val data: ByteArray = Files.readAllBytes(path)
        val audioBytes: ByteString = ByteString.copyFrom(data)

        // Builds the sync recognize request
        val bits = FileUtils.readFileToByteArray(File(output))

        // Builds the sync recognize request
        val config: RecognitionConfig = RecognitionConfig.newBuilder()
            .setEncoding(RecognitionConfig.AudioEncoding.AMR_WB)
            .setSampleRateHertz(16000)
            .setLanguageCode("en-US")
            .build()
        val audio: RecognitionAudio = RecognitionAudio.newBuilder()
            .setContent(ByteString.copyFrom(bits))
            .build()

        // Performs speech recognition on the audio file

        // Performs speech recognition on the audio file
        val response: RecognizeResponse = speechClient.recognize(config, audio)
        val results: List<SpeechRecognitionResult> = response.resultsList

//        for (result in results) {
//            // There can be several alternative transcripts for a given chunk of speech. Just use the
//            // first (most likely) one here.
//
//        }
        val alternative: SpeechRecognitionAlternative = results[0].alternativesList[0]
        speechClient.close()
        if (alternative.transcript.lowercase().isNotEmpty())
            processSpeech(alternative.transcript.lowercase(), image)
    }


    // commands :

    // restricted
    // open main entrance
    // open garage door

    // not restricted
    // turn on bedroom lights
    // turn on hall lights
    // turn off bedroom lights
    // turn off hall lights

    // rooms
    // bedroom - 1, hall - 2, main - 3 , garage - 4

    //appliances
    // lights - 1, door/entrance - 2

    // status
    // on - 1, off - 2

    // example command:

    // open main entrance --- 321
    // open garage door   --- 421
    // turn on bedroom lights --- 111
    // turn on hall lights    --- 211
    // turn off bedroom lights --- 112
    // turn off hall lights    --- 212

    private fun processSpeech(text: String, image: String) {
        Timber.e(text)
        if (text.contains("garage") || text.contains("main")) {
            // its restricted ask shas
            shasCall(text, image)

        } else {
            val builder = java.lang.StringBuilder()

            // room id
            if (text.contains("bed"))
                builder.append(1)
            else if (text.contains("hall") || text.contains("living"))
                builder.append(2)
            else if (text.contains("main"))
                builder.append(3)
            else if (text.contains("garage"))
                builder.append(4)

            // appliances
            if (text.contains("light"))
                builder.append(1)
            else if (text.contains("door") || text.contains("entrance"))
                builder.append(2)

            // status id
            if (text.contains("on") || text.contains("open"))
                builder.append(1)
            else if (text.contains("of") || text.contains("close"))
                builder.append(2)

            Timber.e("$text -- $builder")
            mqttClient.publish("shas", builder.toString())

        }
    }


    private lateinit var mqttClient: MQTTClient

    private fun initMqtt() {
        mqttClient = MQTTClient(this, MQTT_SERVER_URI, "clientId")


        mqttClient.connect("",
            "",
            object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: IMqttToken?) {
                    Log.d(this.javaClass.name, "Connection success")

                    Timber.e("MQTT Connection success")
                }

                override fun onFailure(asyncActionToken: IMqttToken?, exception: Throwable?) {
                    Log.d(this.javaClass.name, "Connection failure: ${exception.toString()}")
                }
            },
            object : MqttCallback {
                override fun messageArrived(topic: String?, message: MqttMessage?) {
                    val msg = "Receive message: ${message.toString()} from topic: $topic"
                    Log.d(this.javaClass.name, msg)

                    Timber.e(msg)
                }

                override fun connectionLost(cause: Throwable?) {
                    Log.d(this.javaClass.name, "Connection lost ${cause.toString()}")
                }

                override fun deliveryComplete(token: IMqttDeliveryToken?) {
                    Log.d(this.javaClass.name, "Delivery complete")
                }
            })
    }

    private fun shasCall(text: String, url: String) {

        Timber.e(url)
        api.getApproval(url).enqueue(object : Callback<String> {
            override fun onResponse(call: Call<String>, response: Response<String>) {
                if (response.isSuccessful) {
                    response.body()?.let {
//                        toast(it)
                        if (response.body().equals("Authorised")) {
                            processAfterCall(text)
                        } else {
                            toast("User not authorised..")
                        }
                    }
                }
            }

            override fun onFailure(call: Call<String>, t: Throwable) {
                t.printStackTrace()
                toast(t.toString())
            }

        })
    }

    private fun processAfterCall(text: String) {
        val builder = java.lang.StringBuilder()

        // room id
        if (text.contains("bed"))
            builder.append(1)
        else if (text.contains("hall") || text.contains("living"))
            builder.append(2)
        else if (text.contains("main"))
            builder.append(3)
        else if (text.contains("garage"))
            builder.append(4)

        // appliances
        if (text.contains("light"))
            builder.append(1)
        else if (text.contains("door") || text.contains("entrance"))
            builder.append(2)

        // status id
        if (text.contains("on") || text.contains("open"))
            builder.append(1)
        else if (text.contains("of") || text.contains("close"))
            builder.append(2)

        Timber.e("$text -- $builder")
        mqttClient.publish("shas", builder.toString())
    }

    private fun toast(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
    }
}