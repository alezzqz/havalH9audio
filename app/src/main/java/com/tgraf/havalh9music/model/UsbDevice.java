/*
 * HavalH9Music - offline audio player for the Haval car head unit
 * Copyright (C) 2026 TermoGRAF
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.tgraf.havalh9music.model;

import java.io.File;

/**
 * Найденное медиаустройство (USB-флешка, SD-карта или внутренняя память).
 */
public class UsbDevice {
    /**
     * Корень накопителя (каталог, с которого читается библиотека).
     */
    public final File root;
    /**
     * Понятное пользователю название устройства.
     */
    public final String label;

    public UsbDevice(File root, String label) {
        this.root = root;
        this.label = label;
    }
}