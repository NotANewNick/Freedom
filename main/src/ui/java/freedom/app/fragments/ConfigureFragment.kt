/*
 * Copyright (c) 2012-2025 Arne Schwabe
 * Distributed under the GNU GPL v2 with additional terms. For full terms see the file doc/LICENSE.txt
 */

package freedom.app.fragments

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.text.Editable
import android.text.TextWatcher
import android.util.Log
import androidx.fragment.app.Fragment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import freedom.app.R
import freedom.app.databinding.FragmentConfigureBinding

class ConfigureFragment : Fragment(), View.OnClickListener {

    lateinit var binding: FragmentConfigureBinding
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentConfigureBinding.inflate(layoutInflater)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.btnConfigure.setOnClickListener(this)
        binding.btnApply.setOnClickListener(this)
        binding.etMsgBody1.setText("AT+GTQSS=gv500,${binding.etApnDevice.text.trim()},,,2,,1,${binding.etDestinationAddress.text.trim()},${binding.etPort.text.trim()},,,,15,,,,0001$")
//        binding.etMsgBody2.setText(Constants.SMSCommands.GLOBAL_CONFIG)
//        binding.etMsgBody3.setText(Constants.SMSCommands.VIRTUAL_IGN_CONFIG)
//        binding.etMsgBody4.setText(Constants.SMSCommands.ACC_VIRTUAL_IGN_CONFIG)

        val textWatcher = object: TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

            }

            override fun afterTextChanged(p0: Editable?) {
                binding.etMsgBody1.setText("AT+GTQSS=gv500,${p0.toString().trim()},,,2,,1,${binding.etDestinationAddress.text.trim()},${binding.etPort.text.trim()},,,,15,,,,0001$")
            }
        }

        val destinationTextWatcher = object: TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

            }

            override fun afterTextChanged(p0: Editable?) {
                binding.etMsgBody1.setText("AT+GTQSS=gv500,${binding.etApnDevice.text.trim()},,,2,,1,${p0.toString().trim()},${binding.etPort.text.trim()},,,,15,,,,0001$")
            }
        }

        val portTextWatcher = object: TextWatcher {
            override fun beforeTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

            }

            override fun onTextChanged(p0: CharSequence?, p1: Int, p2: Int, p3: Int) {

            }

            override fun afterTextChanged(p0: Editable?) {
                binding.etMsgBody1.setText("AT+GTQSS=gv500,${binding.etApnDevice.text.trim()},,,2,,1,${binding.etDestinationAddress.text.trim()},${p0.toString().trim()},,,,15,,,,0001$")
            }
        }
        binding.etApnDevice.addTextChangedListener(textWatcher)
        binding.etDestinationAddress.addTextChangedListener(destinationTextWatcher)
        binding.etPort.addTextChangedListener(portTextWatcher)
    }

    override fun onClick(p0: View?) {
        when (p0?.id) {
            R.id.btnConfigure -> {
//                if (checkValidation()){
//                    val sharedPref = activity?.getPreferences(Context.MODE_PRIVATE) ?: return
//                    with (sharedPref.edit()) {
//                        putInt(getString(R.string.PORT_KEY), binding.etPort.text.trim().toString().toInt())
//                        apply()
//                    }
//                    isSMSPermissionGranted()
//                }
            }
            R.id.btnApply -> {
                if(binding.etPort.text.trim().isNotEmpty()) {
                    //VPNLaunchHelper.startTcpServer(activity, binding.etPort.text.trim().toString().toInt())
                }
            }
        }
    }

    private fun checkValidation(): Boolean {
        if (binding.etApnDevice.text.trim().isEmpty()
            || binding.etSimNumber.text.trim().isEmpty()
            || binding.etDestinationAddress.text.trim().isEmpty()
            || binding.etPort.text.trim().isEmpty()
        ) {
            Toast.makeText(context, "Fill out all fields", Toast.LENGTH_LONG).show()
        } else return true
        return false
    }

    private fun isSMSPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= 23) {
            if (context?.let {
                    ContextCompat.checkSelfPermission(
                        it,
                        Manifest.permission.SEND_SMS
                    )
                } == PackageManager.PERMISSION_GRANTED
            ) {
                Log.v("PHONE PERMISSION", "Permission is granted")
                sendSequentialSms()
                true
            } else {
                Log.v("PHONE PERMISSION", "Permission is revoked")
                activity?.let {
                    ActivityCompat.requestPermissions(
                        it,
                        arrayOf(Manifest.permission.SEND_SMS),
                        1
                    )
                }
                false
            }
        } else { //permission is automatically granted on sdk<23 upon installation
            Log.v("PHONE PERMISSION", "Permission is granted")
            true
        }
    }

    fun sendSequentialSms() {
        var sms1 = binding.etMsgBody1.text.trim().toString() + binding.etMsgBody2.text.trim().toString()
        var sms2 = binding.etMsgBody3.text.trim().toString() + binding.etMsgBody4.text.trim().toString()
        var sms3 = ""
        var sms4 = ""
        if(sms1.length > 160){
            sms1 = binding.etMsgBody1.text.trim().toString()
            sms2 = binding.etMsgBody2.text.trim().toString() + binding.etMsgBody3.text.trim().toString() + binding.etMsgBody4.text.trim().toString()
        }
        if(sms2.length > 160) {
            sms2 = binding.etMsgBody2.text.trim().toString()
            sms3 = binding.etMsgBody3.text.trim().toString() + binding.etMsgBody4.text.trim().toString()
        }
        if(sms3.length > 160) {
            sms3 = binding.etMsgBody3.text.trim().toString()
            sms4 = binding.etMsgBody4.text.trim().toString()
        }

        if (sms1.isNotEmpty())
            sendSMS(
                binding.etSimNumber.text.trim().toString(),
                sms1
            )
        if (sms2.isNotEmpty())
            sendSMS(
                binding.etSimNumber.text.trim().toString(),
                sms2
            )
        if (sms3.isNotEmpty())
            sendSMS(
                binding.etSimNumber.text.trim().toString(),
                sms3
            )
        if(sms4.isNotEmpty())
            sendSMS(
                binding.etSimNumber.text.trim().toString(),
                sms4
            )
    }

    private fun sendSMS(phoneNumber: String, message: String) {
        val SENT = "SMS_SENT"
        val DELIVERED = "SMS_DELIVERED"
        val sentPI: PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(SENT), 0
        )
        val deliveredPI: PendingIntent = PendingIntent.getBroadcast(
            context, 0,
            Intent(DELIVERED), 0
        )

        //---when the SMS has been sent---
        activity?.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(arg0: Context?, arg1: Intent?) {
                when (resultCode) {
                    Activity.RESULT_OK -> {
                        Toast.makeText(
                            context, "SMS sent",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    SmsManager.RESULT_ERROR_GENERIC_FAILURE -> Toast.makeText(
                        context, "Generic failure",
                        Toast.LENGTH_SHORT
                    ).show()
                    SmsManager.RESULT_ERROR_NO_SERVICE -> Toast.makeText(
                        context, "No service",
                        Toast.LENGTH_SHORT
                    ).show()
                    SmsManager.RESULT_ERROR_NULL_PDU -> Toast.makeText(
                        context, "Null PDU",
                        Toast.LENGTH_SHORT
                    ).show()
                    SmsManager.RESULT_ERROR_RADIO_OFF -> Toast.makeText(
                        context, "Radio off",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, IntentFilter(SENT))

        //---when the SMS has been delivered---
        activity?.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(arg0: Context?, arg1: Intent?) {
                when (resultCode) {
                    Activity.RESULT_OK -> {
                        Toast.makeText(
                            context, "SMS delivered",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                    Activity.RESULT_CANCELED -> Toast.makeText(
                        context, "SMS not delivered",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }, IntentFilter(DELIVERED))
        val smsManager: SmsManager? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context?.getSystemService<SmsManager>(SmsManager::class.java)
        } else {
            SmsManager.getDefault()
        }
        smsManager?.sendTextMessage(phoneNumber, null, message, sentPI, deliveredPI)
    }
}