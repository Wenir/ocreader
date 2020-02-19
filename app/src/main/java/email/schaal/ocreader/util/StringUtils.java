/*
 * Copyright (C) 2015 Daniel Schaal <daniel@schaal.email>
 *
 * This file is part of OCReader.
 *
 * OCReader is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * OCReader is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with OCReader.  If not, see <http://www.gnu.org/licenses/>.
 *
 */

package email.schaal.ocreader.util;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import android.text.Html;
import android.text.format.DateUtils;

import java.util.Date;

import email.schaal.ocreader.R;

/**
 * Utility class to handle Strings.
 */
public class StringUtils {
    @NonNull
    public static String getByLine(@NonNull Context context, String template, @Nullable String author) {
        if(author != null) {
            return String.format(template, context.getString(R.string.by_author, author));
        } else {
            return "";
        }
    }

    @NonNull
    public static String getTimeSpanString(Context context, Date startDate) {
        return getTimeSpanString(context, startDate, new Date());
    }

    @NonNull
    public static String getTimeSpanString(Context context, Date startDate, Date endDate) {
        String timeSpanString;

        long timeDiff = (endDate.getTime() - startDate.getTime());

        if(timeDiff <= 0)
            timeSpanString = context.getString(R.string.now);
        else if(timeDiff <= 59 * DateUtils.MINUTE_IN_MILLIS)
            timeSpanString = context.getString(R.string.minutes, timeDiff/DateUtils.MINUTE_IN_MILLIS);
        else if(timeDiff <= 23 * DateUtils.HOUR_IN_MILLIS)
            timeSpanString = context.getString(R.string.hours, timeDiff/DateUtils.HOUR_IN_MILLIS);
        else
            timeSpanString = context.getString(R.string.days, timeDiff / DateUtils.DAY_IN_MILLIS);
        return timeSpanString;
    }

    @NonNull
    public static String cleanString(@NonNull String source) {
        return Html.fromHtml(source).toString();
    }

    @Nullable
    public static String emptyToNull(@NonNull String source) {
        if(source.isEmpty())
            return null;
        return source;
    }

    @NonNull
    public static String nullToEmpty(@Nullable String source) {
        if(source == null)
            return "";
        return source;
    }
}
