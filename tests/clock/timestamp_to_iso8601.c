int is_leap_year(int year) {
    return (year % 4 == 0 && year % 100 != 0) || (year % 400 == 0);
}

int days_in_month(int month, int year) {
    const int days[12] = {31, 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31};
    if (month == 1 && is_leap_year(year)) {
        return 29;
    }
    return days[month];
}

void int_to_str(int num, char* buffer, int width) {
    for (int i = width - 1; i >= 0; i--) {
        buffer[i] = '0' + (num % 10);
        num /= 10;
    }
    buffer[width] = '\0';
}

void int_to_padded_str(int num, char* buffer, int width) {
    int i;
    for (i = 0; i < width; i++) {
        buffer[i] = '0';
    }

    i = width - 1;
    while (num > 0 && i >= 0) {
        buffer[i--] = '0' + (num % 10);
        num /= 10;
    }
    buffer[width] = '\0';
}

void timestamp_to_iso8601(unsigned long timestamp, char* buffer, int buffer_size) {
    unsigned long days_since_epoch = timestamp / 86400;
    unsigned long seconds_in_day = timestamp % 86400;
    
    int hour = seconds_in_day / 3600;
    int minute = (seconds_in_day % 3600) / 60;
    int second = seconds_in_day % 60;

    int year = 1970;
    int month = 0;
    int day = 0;

    while (1) {
        int days_in_year = is_leap_year(year) ? 366 : 365;
        if (days_since_epoch < days_in_year)
            break;
        days_since_epoch -= days_in_year;
        year++;
    }

    for (month = 0; month < 12; month++) {
        int days_this_month = days_in_month(month, year);
        if (days_since_epoch < days_this_month)
            break;
        days_since_epoch -= days_this_month;
    }
    month++;
    day = days_since_epoch + 1;
    if (buffer_size < 21) {
        buffer[0] = '\0';
        return;
    }
    
    char year_str[5], month_str[3], day_str[3];
    char hour_str[3], minute_str[3], second_str[3];
    
    int_to_padded_str(year, year_str, 4);
    int_to_padded_str(month, month_str, 2);
    int_to_padded_str(day, day_str, 2);
    int_to_padded_str(hour, hour_str, 2);
    int_to_padded_str(minute, minute_str, 2);
    int_to_padded_str(second, second_str, 2);

    buffer[0] = year_str[0];
    buffer[1] = year_str[1];
    buffer[2] = year_str[2];
    buffer[3] = year_str[3];
    buffer[4] = '-';
    buffer[5] = month_str[0];
    buffer[6] = month_str[1];
    buffer[7] = '-';
    buffer[8] = day_str[0];
    buffer[9] = day_str[1];
    buffer[10] = 'T';
    buffer[11] = hour_str[0];
    buffer[12] = hour_str[1];
    buffer[13] = ':';
    buffer[14] = minute_str[0];
    buffer[15] = minute_str[1];
    buffer[16] = ':';
    buffer[17] = second_str[0];
    buffer[18] = second_str[1];
    buffer[19] = 'Z';
    buffer[20] = '\0';
}