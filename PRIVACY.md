# Opedrgent Privacy Policy

## Health Connect Data

Opedrgent uses the Health Connect API to read your health and fitness data, including:

- **Steps** (daily step count)
- **Heart Rate** (average, min, max)
- **Calories Burned** (active and total)
- **Distance** (walking/running distance)
- **Sleep** (sleep session duration)
- **Exercise** (workout sessions)

### How We Use Your Health Data

- Health data is read **only on your device** and is sent to the AI model as context for health-related conversations.
- Health data is **never uploaded to our servers** or any third-party service.
- Health data is **not stored** persistently by Opedrgent beyond the current session context.
- Health data is **not shared** with any third party.

### Permissions

Opedrgent requests the following Health Connect permissions:

- `android.permission.health.READ_STEPS`
- `android.permission.health.READ_HEART_RATE`
- `android.permission.health.READ_TOTAL_CALORIES_BURNED`
- `android.permission.health.READ_DISTANCE`
- `android.permission.health.READ_SLEEP`
- `android.permission.health.READ_ACTIVE_CALORIES_BURNED`
- `android.permission.health.READ_EXERCISE`
- `android.permission.ACTIVITY_RECOGNITION`

You can revoke these permissions at any time through your device settings or the Health Connect app.

### Data Retention

Opedrgent does not retain health data. All health information is processed in-memory and discarded after the AI conversation context is cleared.

## Contact

If you have questions about this privacy policy, please open an issue on our [GitHub repository](https://github.com/HaohanHe/Opedrgent).
