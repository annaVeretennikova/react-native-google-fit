/**
 * Copyright (c) 2017-present, Stanislav Doskalenko - doskalenko.s@gmail.com
 * All rights reserved.
 * <p>
 * This source code is licensed under the MIT-style license found in the
 * LICENSE file in the root directory of this source tree.
 * <p>
 * Based on Asim Malik android source code, copyright (c) 2015
 **/

package com.reactnative.googlefit;

import android.os.AsyncTask;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.ReactContext;
import com.facebook.react.bridge.ReadableArray;
import com.facebook.react.bridge.ReadableMap;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.google.android.gms.fitness.Fitness;
import com.google.android.gms.fitness.data.Bucket;
import com.google.android.gms.fitness.data.DataPoint;
import com.google.android.gms.fitness.data.DataSet;
import com.google.android.gms.fitness.data.DataSource;
import com.google.android.gms.fitness.data.DataType;
import com.google.android.gms.fitness.data.Field;
import com.google.android.gms.fitness.request.DataReadRequest;
import com.google.android.gms.fitness.result.DataReadResult;

import org.json.JSONObject;

import java.sql.Array;
import java.text.DateFormat;
import java.text.Format;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.TimeUnit;

class BMR {
    public float value;
    public long timestamp;

    public BMR(float val, long ts) {
        value = val;
        timestamp = ts;
    }
}

public class CalorieHistory {
    private ReactContext mReactContext;
    private GoogleFitManager googleFitManager;
    private DataSet FoodDataSet;

    private static final String TAG = "CalorieHistory";

    public CalorieHistory(ReactContext reactContext, GoogleFitManager googleFitManager) {
        this.mReactContext = reactContext;
        this.googleFitManager = googleFitManager;
    }

    public ReadableArray aggregateDataByDate(long startTime, long endTime, boolean basalCalculation) {

        DateFormat dateFormat = DateFormat.getDateInstance();
        Log.i(TAG, "Range Start: " + dateFormat.format(startTime));
        Log.i(TAG, "Range End: " + dateFormat.format(endTime));

        //Check how much calories were expended in specific days.
        DataReadRequest readRequest = new DataReadRequest.Builder()
                .aggregate(DataType.TYPE_CALORIES_EXPENDED, DataType.AGGREGATE_CALORIES_EXPENDED)
                .bucketByTime(1, TimeUnit.DAYS)
                .setTimeRange(startTime, endTime, TimeUnit.MILLISECONDS)
                .build();

        DataReadResult dataReadResult = Fitness.HistoryApi.readData(googleFitManager.getGoogleApiClient(), readRequest).await(1, TimeUnit.MINUTES);


        WritableArray map = Arguments.createArray();

        // collects all recent basals
        List<BMR> basals = new ArrayList<>();

        try {
            basals = getBasals();
        } catch (Exception e) {
            e.printStackTrace();
        }

        //Used for aggregated data
        if (dataReadResult.getBuckets().size() > 0) {
            Log.i(TAG, "Number of buckets: " + dataReadResult.getBuckets().size());
            for (Bucket bucket : dataReadResult.getBuckets()) {
                List<DataSet> dataSets = bucket.getDataSets();
                for (DataSet dataSet : dataSets) {
                    processDataSet(dataSet, map, basalCalculation, basals);
                }
            }
        }
        //Used for non-aggregated data
        else if (dataReadResult.getDataSets().size() > 0) {
            Log.i(TAG, "Number of returned DataSets: " + dataReadResult.getDataSets().size());
            for (DataSet dataSet : dataReadResult.getDataSets()) {
                processDataSet(dataSet, map, basalCalculation, basals);
            }
        }

        return map;
    }

    private void processDataSet(DataSet dataSet, WritableArray map, boolean basalCalculation, List<BMR> basals) {
        Log.i(TAG, "Data returned for Data type: " + dataSet.getDataType().getName());
        DateFormat dateFormat = DateFormat.getDateInstance();
        DateFormat timeFormat = DateFormat.getTimeInstance();
        Format formatter = new SimpleDateFormat("EEE");
        WritableMap stepMap = Arguments.createMap();


        for (DataPoint dp : dataSet.getDataPoints()) {
            Log.i(TAG, "Data point:");
            Log.i(TAG, "\tType: " + dp.getDataType().getName());
            Log.i(TAG, "\tStart: " + dateFormat.format(dp.getStartTime(TimeUnit.MILLISECONDS)) + " " + timeFormat.format(dp.getStartTime(TimeUnit.MILLISECONDS)));
            Log.i(TAG, "\tEnd: " + dateFormat.format(dp.getEndTime(TimeUnit.MILLISECONDS)) + " " + timeFormat.format(dp.getStartTime(TimeUnit.MILLISECONDS)));

            String day = formatter.format(new Date(dp.getStartTime(TimeUnit.MILLISECONDS)));
            Log.i(TAG, "Day: " + day);

            for (Field field : dp.getDataType().getFields()) {
                Log.i("History", "\tField: " + field.getName() +
                        " Value: " + dp.getValue(field));

                stepMap.putString("day", day);
                stepMap.putDouble("startDate", dp.getStartTime(TimeUnit.MILLISECONDS));
                stepMap.putDouble("endDate", dp.getEndTime(TimeUnit.MILLISECONDS));

                float basal = getClosestBasal(dp.getEndTime(TimeUnit.MILLISECONDS), basals);
                float valueWithoutBasal = dp.getValue(field).asFloat() - basal;
                stepMap.putDouble("calorie", valueWithoutBasal < 0 ? 0 : valueWithoutBasal);
                Log.i("TEST", "\tBasal: " + basal +
                        " Calorie: " + valueWithoutBasal);
                map.pushMap(stepMap);
            }
        }
    }

    // utility function that returns a correct basal for calolories depending on existing basals and the calories timestamp
    private float getClosestBasal(long ts, List<BMR> basals) {
        float basal = basals.size() > 0? basals.get(0).value : 0;

        for (BMR bmr : basals) {
            if (ts >= bmr.timestamp) {
                return basal;
            }

            basal = bmr.value;
        }

        return basal;
    }

    // utility function that gets all recent basals
    private List<BMR> getBasals() throws Exception {
        List<BMR> basals = new ArrayList<>();
        long et = new Date().getTime();

        while(basals.size() == 0) {
            Calendar cal = java.util.Calendar.getInstance();
            cal.setTime(new Date(et));
            cal.add(Calendar.MONTH, -1);
            long nst = cal.getTimeInMillis();

            DataReadRequest.Builder builder = new DataReadRequest.Builder();
            builder.aggregate(DataType.TYPE_BASAL_METABOLIC_RATE, DataType.AGGREGATE_BASAL_METABOLIC_RATE_SUMMARY);
            builder.bucketByTime(1, TimeUnit.DAYS);
            builder.setTimeRange(nst, et, TimeUnit.MILLISECONDS);
            DataReadRequest readRequest = builder.build();

            DataReadResult dataReadResult = Fitness.HistoryApi.readData(googleFitManager.getGoogleApiClient(), readRequest).await();

            if (dataReadResult.getStatus().isSuccess()) {
                ArrayList<Bucket> buckets = (ArrayList<Bucket>) dataReadResult.getBuckets();

                for (int i = buckets.size() - 1; i >= 0; i--) {
                    DataSet ds = buckets.get(i).getDataSet(DataType.AGGREGATE_BASAL_METABOLIC_RATE_SUMMARY);

                    if(ds != null) {
                        int avgsN = 0;
                        float basalAVG = 0;
                        long ts = 0;

                        for (DataPoint dp : ds.getDataPoints()) {
                            float value = dp.getValue(Field.FIELD_AVERAGE).asFloat();

                            if(value > 0) {
                                ts = dp.getEndTime(TimeUnit.MILLISECONDS);
                                basalAVG += value;
                                avgsN++;
                            }
                        }

                        if(basalAVG > 0) {
                            basals.add(new BMR( basalAVG / avgsN, ts));
                        }
                    }
                }

                et = nst;
            } else throw new Exception(dataReadResult.getStatus().getStatusMessage());
        }

        return basals;
    }

    public boolean saveFood(ReadableMap foodSample) {
        this.FoodDataSet = createDataForRequest(
                DataType.TYPE_NUTRITION,    // for height, it would be DataType.TYPE_HEIGHT
                DataSource.TYPE_RAW,
                foodSample.getMap("nutrients").toHashMap(),
                foodSample.getInt("mealType"),                  // meal type
                foodSample.getString("foodName"),               // food name
                (long)foodSample.getDouble("date"),             // start time
                (long)foodSample.getDouble("date"),             // end time
                TimeUnit.MILLISECONDS                // Time Unit, for example, TimeUnit.MILLISECONDS
        );
        new CalorieHistory.InsertAndVerifyDataTask(this.FoodDataSet).execute();

        return true;
    }

    //Async fit data insert
    private class InsertAndVerifyDataTask extends AsyncTask<Void, Void, Void> {

        private DataSet FoodDataset;

        InsertAndVerifyDataTask(DataSet dataset) {
            this.FoodDataset = dataset;
        }

        protected Void doInBackground(Void... params) {
            // Create a new dataset and insertion request.
            DataSet dataSet = this.FoodDataset;

            // [START insert_dataset]
            // Then, invoke the History API to insert the data and await the result, which is
            // possible here because of the {@link AsyncTask}. Always include a timeout when calling
            // await() to prevent hanging that can occur from the service being shutdown because
            // of low memory or other conditions.
            //Log.i(TAG, "Inserting the dataset in the History API.");
            com.google.android.gms.common.api.Status insertStatus =
                    Fitness.HistoryApi.insertData(googleFitManager.getGoogleApiClient(), dataSet)
                            .await(1, TimeUnit.MINUTES);

            // Before querying the data, check to see if the insertion succeeded.
            if (!insertStatus.isSuccess()) {
                //Log.i(TAG, "There was a problem inserting the dataset.");
                return null;
            }

            //Log.i(TAG, "Data insert was successful!");

            return null;
        }
    }

    /**
     * This method creates a dataset object to be able to insert data in google fit
     *
     * @param dataType       DataType Fitness Data Type object
     * @param dataSourceType int Data Source Id. For example, DataSource.TYPE_RAW
     * @param values         Object Values for the fitness data. They must be HashMap
     * @param mealType       int Value of enum. For example Field.MEAL_TYPE_SNACK
     * @param name           String Dish name. For example "banana"
     * @param startTime      long Time when the fitness activity started
     * @param endTime        long Time when the fitness activity finished
     * @param timeUnit       TimeUnit Time unit in which period is expressed
     * @return
     */
    private DataSet createDataForRequest(DataType dataType, int dataSourceType,
                                         HashMap<String, Object> values, int mealType, String name,
                                         long startTime, long endTime, TimeUnit timeUnit) {

        DataSource dataSource = new DataSource.Builder()
                .setAppPackageName(GoogleFitPackage.PACKAGE_NAME)
                .setDataType(dataType)
                .setType(dataSourceType)
                .build();

        DataSet dataSet = DataSet.create(dataSource);
        DataPoint dataPoint = dataSet.createDataPoint().setTimeInterval(startTime, endTime, timeUnit);

        dataPoint.getValue(Field.FIELD_FOOD_ITEM).setString(name);
        dataPoint.getValue(Field.FIELD_MEAL_TYPE).setInt(mealType);
        for (String key : values.keySet()) {
            Float value = Float.valueOf(values.get(key).toString());

            if (value > 0) {
                dataPoint.getValue(Field.FIELD_NUTRIENTS).setKeyValue(key, value);
            }
        }

        dataSet.add(dataPoint);

        return dataSet;
    }
}
