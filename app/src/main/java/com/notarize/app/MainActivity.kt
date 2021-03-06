package com.notarize.app


import android.app.Activity
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.notarize.app.ext.createLoadingDialog
import io.jsonwebtoken.io.Encoders
import kotlinx.android.synthetic.main.activity_main.*
import okhttp3.MediaType
import okhttp3.RequestBody
import org.json.JSONObject
import org.web3j.crypto.Credentials
import org.web3j.crypto.Hash
import org.web3j.crypto.Sign
import org.web3j.protocol.core.methods.response.TransactionReceipt
import org.web3j.tx.gas.DefaultGasProvider
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import java.util.*


class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        var fileHash = intent.getStringExtra(EXTRA_FILE_HASH);
        if (fileHash == null) {
            fileHash = "This is a temporary text"
        }
        uploadButton.setOnClickListener{
            val dialog = createLoadingDialog()
            dialog.show()

            val ipfsManager = IpfsManager(
                getString(R.string.pinata_header_1),
                getString(R.string.pinata_header_2))

            val ethereumManager = EthereumManager()
            val web3 = ethereumManager.connectToNetwork(getString(R.string.testnet_infura_endpoint))
            val password = getString(R.string.temp_password)

            var notaryCredentials: Credentials? = ethereumManager.loadCredentials(
                this,
                getString(R.string.k_WalletFileName),
                password
            )
            //var adversaryCredentials : Credentials? = ethereumManager.loadCredentials(this, getString(R.string.k_UnauthorizedWalletFileName), password)

            var jwtHeader = JSONObject()
            jwtHeader.put("typ", "JWT")
                .put("alg", "ES256")

            var jwtPayload = JSONObject()
            jwtPayload.put("iss", notaryCredentials?.address)
                .put("fileHash", fileHash)
                .put("isa", Date().time)

            var encodedMessage =
                Encoders.BASE64URL.encode(jwtHeader.toString().toByteArray()) + "." + Encoders.BASE64URL.encode(
                    jwtPayload.toString().toByteArray()
                )
            Log.d("TEST", "JWT before signature: " + encodedMessage)
            val signatureData = Sign.signMessage(
                Hash.sha256(encodedMessage.toByteArray()),
                notaryCredentials?.ecKeyPair
            )
            encodedMessage += "." + Encoders.BASE64URL.encode(signatureData.toString().toByteArray())

            val jwtToken = encodedMessage
            val content = jwtToken

            // Create a request body with file and image media type
            val fileReqBody = RequestBody.create(MediaType.parse("application/jwt"), content)
            val fileName = "filehash.jwt"

            //Upload file to IPFS
            ipfsManager.uploadFile(fileName, fileReqBody, object : Callback<IpfsObject> {
                override fun onFailure(call: Call<IpfsObject>, t: Throwable) {
                    Log.d("TEST", t.message)
                }

                override fun onResponse(call: Call<IpfsObject>, response: Response<IpfsObject>) {

                    if (response.code() == 200) {
                        Log.d("TEST", "Something worked")
                        val body = response.body()!!
                        Log.d("TEST", "IPFSHash: " + body.IpfsHash)


                        val smartContract: TallyLock = TallyLock.load(getString(R.string.tallyLockSmartContractAddress),
                            web3,
                            notaryCredentials,
                            DefaultGasProvider.GAS_PRICE,
                            DefaultGasProvider.GAS_LIMIT)
                        //var smartContract = TallyLock.load(getString(R.string.tallyLockSmartContractAddress), web3, notaryCredentials, DefaultGasProvider.GAS_PRICE, DefaultGasProvider.GAS_LIMIT)


                        //If Uploaded correctly send to the SmartContract for logging
                        var receipt = smartContract.signDocument(
                            fileHash,
                            body.IpfsHash).sendAsync().whenCompleteAsync {
                                t: TransactionReceipt?, u: Throwable? ->
                            if (t != null ) {
                                Log.d("TEXT", "Transaction receipt: " + t.transactionHash)
                                setResult(Activity.RESULT_OK)
                                finish()
                            } else {
                                setResult(Activity.RESULT_CANCELED)
                                finish()
                                Log.d("TEXT", "Something happened: " + u?.message)
                            }
                        }

                    } else {
                        Log.d("TEST", "Something Kind Of worked")
                        Log.d("TEST", "CODE " + response.code())
                    }
                }

            })

        }
    }
}
