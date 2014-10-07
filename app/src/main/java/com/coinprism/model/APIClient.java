package com.coinprism.model;

import org.bitcoinj.core.Transaction;
import org.bitcoinj.core.Utils;

import org.apache.http.HttpResponse;
import org.apache.http.HttpStatus;
import org.apache.http.HttpVersion;
import org.apache.http.StatusLine;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpRequestBase;
import org.apache.http.conn.ClientConnectionManager;
import org.apache.http.conn.scheme.PlainSocketFactory;
import org.apache.http.conn.scheme.Scheme;
import org.apache.http.conn.scheme.SchemeRegistry;
import org.apache.http.conn.ssl.SSLSocketFactory;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.DefaultHttpClient;
import org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager;
import org.apache.http.params.BasicHttpParams;
import org.apache.http.params.HttpParams;
import org.apache.http.params.HttpProtocolParams;
import org.apache.http.protocol.HTTP;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.math.BigInteger;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;

import javax.net.ssl.HttpsURLConnection;

public class APIClient
{
    private final String baseUrl;
    private final HashMap<String, AssetDefinition> cache = new HashMap<String, AssetDefinition>();
    private final static String userAgent = "Coinprism Android";

    public APIClient(String baseUrl)
    {
        this.baseUrl = baseUrl;
    }

    public AssetDefinition getAssetDefinition(String address)
    {
        return cache.get(address);
    }

    public Collection<AssetDefinition> getAllAssetDefinitions()
    {
        return cache.values();
    }

    private AssetDefinition fetchAssetDefinition(String address) throws IOException, APIException
    {
        AssetDefinition definition = cache.get(address);
        if (definition == null)
        {
            String httpResponse = executeHttpGet(this.baseUrl + "/v1/assets/" + address);

            try
            {
                JSONObject jObject = new JSONObject(httpResponse);

                definition = new AssetDefinition(
                    jObject.getString("asset_address"),
                    jObject.getString("name"),
                    jObject.getString("name_short"),
                    jObject.getInt("divisibility"),
                    jObject.getString("icon_url"));
            }
            catch (JSONException ex)
            {
                definition = new AssetDefinition(address);
            }

            cache.put(address, definition);
        }

        return definition;
    }

    public AddressBalance getAddressBalance(String address) throws IOException, JSONException, APIException
    {
        String json = executeHttpGet(this.baseUrl + "/v1/addresses/" + address);

        JSONObject jObject = new JSONObject(json);
        JSONArray assets = jObject.getJSONArray("assets");
        Long bitcoinBalance = jObject.getLong("unconfirmed_balance") + jObject.getLong("balance");

        ArrayList<AssetBalance> assetBalances = new ArrayList<AssetBalance>();

        for (int i = 0; i < assets.length(); i++)
        {
            JSONObject assetObject = (JSONObject) assets.get(i);

            String assetAddress = assetObject.getString("address");

            BigInteger quantity = new BigInteger(assetObject.getString("balance"))
                    .add(new BigInteger(assetObject.getString("unconfirmed_balance")));

            assetBalances.add(new AssetBalance(fetchAssetDefinition(assetAddress), quantity));
        }

        return new AddressBalance(bitcoinBalance, assetBalances);
    }

    public List<SingleAssetTransaction> getTransactions(String address)
        throws IOException, JSONException, ParseException, APIException
    {
        String json = executeHttpGet(
            this.baseUrl + "/v1/addresses/" + address + "/transactions");

        JSONArray transactions = new JSONArray(json);

        ArrayList<SingleAssetTransaction> assetBalances = new ArrayList<SingleAssetTransaction>();

        for (int i = 0; i < transactions.length(); i++)
        {
            JSONObject transactionObject = (JSONObject) transactions.get(i);
            String transactionId = transactionObject.getString("hash");

            Date date = null;
            if (!transactionObject.isNull("block_time"))
                date = parseDate(transactionObject.getString("block_time"));

            HashMap<String, BigInteger> quantities = new HashMap<String, BigInteger>();
            BigInteger satoshiDelta = BigInteger.ZERO;

            JSONArray inputs = transactionObject.getJSONArray("inputs");
            for (int j = 0; j < inputs.length(); j++)
            {
                JSONObject input = (JSONObject) inputs.get(j);
                if (isAddress(input, address))
                {
                    if (!input.isNull("asset_address"))
                        addQuantity(quantities,
                            input.getString("asset_address"),
                            new BigInteger(input.getString("asset_quantity")).negate());

                    satoshiDelta = satoshiDelta.subtract(
                        BigInteger.valueOf(input.getLong("value")));
                }
            }

            JSONArray outputs = transactionObject.getJSONArray("outputs");
            for (int j = 0; j < outputs.length(); j++)
            {
                JSONObject output = (JSONObject) outputs.get(j);
                if (isAddress(output, address))
                {
                    if (!output.isNull("asset_address"))
                        addQuantity(quantities,
                            output.getString("asset_address"),
                            new BigInteger(output.getString("asset_quantity")));

                    satoshiDelta = satoshiDelta.add(
                        BigInteger.valueOf(output.getLong("value")));
                }
            }

            if (!satoshiDelta.equals(BigInteger.ZERO))
                assetBalances.add(
                    new SingleAssetTransaction(transactionId, date, null, satoshiDelta));

            for (String key : quantities.keySet())
            {
                assetBalances.add(new SingleAssetTransaction(
                    transactionId,
                    date,
                    getAssetDefinition(key),
                    quantities.get(key)));
            }
        }

        return assetBalances;
    }

    public Transaction buildTransaction(String fromAddress, String toAddress, String amount, String assetAddress,
        long fees)
        throws JSONException, IOException, APIException
    {
        try
        {
            JSONObject toObject = new JSONObject();
            toObject.put("address", toAddress);
            toObject.put("amount", amount);
            if (assetAddress != null)
                toObject.put("asset_address", assetAddress);

            JSONArray array = new JSONArray();
            array.put(toObject);
            JSONObject postData = new JSONObject();
            postData.put("fees", fees);
            postData.put("from", fromAddress);
            postData.put("to", array);

            String result;
            if (assetAddress != null)
                result = executeHttpPost(this.baseUrl + "/v1/sendasset?format=raw", postData.toString());
            else
                result = executeHttpPost(this.baseUrl + "/v1/sendbitcoin?format=raw", postData.toString());

            JSONObject jsonResponse = new JSONObject(result);

            byte[] data = hexStringToByteArray(jsonResponse.getString("raw"));
            Transaction transaction = new Transaction(
                WalletState.getState().getConfiguration().getNetworkParameters(),
                data);
            transaction.ensureParsed();

            return transaction;
        }
        catch (UnsupportedEncodingException ex)
        {
            return null;
        }
    }

    private static byte[] hexStringToByteArray(String s)
    {
        int len = s.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2)
        {
            data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4)
                + Character.digit(s.charAt(i + 1), 16));
        }
        return data;
    }

    public static String byteArrayToHexString(byte[] bytes)
    {
        final char[] hexArray = "0123456789ABCDEF".toCharArray();

        char[] hexChars = new char[bytes.length * 2];
        for (int j = 0; j < bytes.length; j++)
        {
            int v = bytes[j] & 0xFF;
            hexChars[j * 2] = hexArray[v >>> 4];
            hexChars[j * 2 + 1] = hexArray[v & 0x0F];
        }
        return new String(hexChars);
    }

    public String broadcastTransaction(Transaction transaction) throws IOException, JSONException, APIException
    {
        String serializedTransaction = byteArrayToHexString(transaction.bitcoinSerialize());

        try
        {
            String result = executeHttpPost(
                this.baseUrl + "/v1/sendrawtransaction", "\"" + serializedTransaction + "\"");

            return result.substring(1, result.length() - 2);
        }
        catch (UnsupportedEncodingException ex)
        {
            return null;
        }
    }

    private void addQuantity(HashMap<String, BigInteger> map, String assetAddress,
        BigInteger quantity)
    {
        if (!map.containsKey(assetAddress))
            map.put(assetAddress, quantity);
        else
            map.put(assetAddress, quantity.add(map.get(assetAddress)));
    }

    private static Date parseDate(String input) throws java.text.ParseException
    {
        SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss");

        return df.parse(input.substring(0, 21));
    }

    private boolean isAddress(JSONObject member, String localAddress) throws JSONException
    {
        JSONArray addresses = member.getJSONArray("addresses");

        return addresses.length() == 1 && addresses.getString(0).equals(localAddress);
    }

    private static String executeHttpGet(String url) throws IOException, APIException
    {
        URL target = new URL(url);
        HttpsURLConnection connection = (HttpsURLConnection) target.openConnection();

        connection.setRequestMethod("GET");
        connection.setRequestProperty("User-Agent", userAgent);

        return getHttpResponse(connection);
    }

    private static String executeHttpPost(String url, String body) throws IOException, APIException
    {
        URL target = new URL(url);
        HttpsURLConnection connection = (HttpsURLConnection) target.openConnection();

        connection.setRequestMethod("POST");
        connection.setRequestProperty("User-Agent", userAgent);
        connection.setRequestProperty("Content-Type", "application/json");

        OutputStream output = null;
        try
        {
            output = connection.getOutputStream();
            output.write(body.getBytes("UTF-8"));
        }
        finally
        {
            if (output != null)
                output.close();
        }

        return getHttpResponse(connection);
    }

    private static String getHttpResponse(HttpsURLConnection connection) throws IOException, APIException
    {
        int responseCode = connection.getResponseCode();

        if (responseCode < 400)
        {
            InputStream inputStream = new BufferedInputStream(connection.getInputStream());

            return readStream(inputStream);
        }
        else
        {
            InputStream inputStream = new BufferedInputStream(connection.getErrorStream());
            String response = readStream(inputStream);
            try
            {
                JSONObject error = new JSONObject(response);
                String errorCode = error.getString("ErrorCode");
                String subCode = error.optString("SubCode");

                throw new APIException(errorCode, subCode);
            }
            catch (JSONException exception)
            {
                throw new IOException(exception.getMessage());
            }
        }
    }

    private static String readStream(InputStream in)
    {
        BufferedReader reader = null;
        StringBuilder response = new StringBuilder();
        try
        {
            reader = new BufferedReader(new InputStreamReader(in));
            String line = "";
            while ((line = reader.readLine()) != null)
            {
                response.append(line);
            }
        }
        catch (IOException e)
        {
            e.printStackTrace();
        }
        finally
        {
            if (reader != null)
            {
                try
                {
                    reader.close();
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            }
        }
        return response.toString();
    }
}
