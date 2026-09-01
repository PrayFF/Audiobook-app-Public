package com.pray.booklisten.data

import androidx.room.TypeConverter

class Converters {
    @TypeConverter fun sourceTypeToString(value: SourceType): String = value.name
    @TypeConverter fun stringToSourceType(value: String): SourceType = SourceType.valueOf(value)
}

