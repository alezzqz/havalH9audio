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
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */
package com.tgraf.havalh9music.ui;

import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.content.res.AppCompatResources;
import androidx.lifecycle.Observer;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.tgraf.havalh9music.R;
import com.tgraf.havalh9music.databinding.DialogGenresBinding;
import com.tgraf.havalh9music.databinding.ItemGenreBinding;
import com.tgraf.havalh9music.util.AppearanceSettings;
import com.tgraf.havalh9music.util.GenreFilter;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class GenreFilterDialog {
    private final AppCompatActivity activity;
    private final PlayerViewModel vm;
    private final DialogGenresBinding binding;
    private final AlertDialog dialog;
    private final GenreAdapter adapter = new GenreAdapter();
    private PlayerViewModel.GenreState state;
    private Set<String> selected = new HashSet<>();
    private boolean all;
    private boolean updating;

    private GenreFilterDialog(AppCompatActivity activity, PlayerViewModel vm) {
        this.activity = activity;
        this.vm = vm;
        binding = DialogGenresBinding.inflate(activity.getLayoutInflater());
        dialog = new MaterialAlertDialogBuilder(activity,
                R.style.ThemeOverlay_HavalH9Music_GenreDialog)
                .setView(binding.getRoot()).create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(
                    AppCompatResources.getDrawable(activity, R.drawable.bg_eq_window));
        }
    }

    public static void show(AppCompatActivity activity, PlayerViewModel vm) {
        if (vm.getGenreState().getValue() != null) {
            new GenreFilterDialog(activity, vm).show();
        }
    }

    private void show() {
        binding.genres.setAdapter(adapter);
        binding.genres.setOnItemClickListener((parent, view, position, id) -> {
            GenreFilter.Option option = adapter.visible.get(position);
            if (all) {
                selected = state.catalog.keys();
                all = false;
            }
            if (!selected.remove(option.key)) {
                selected.add(option.key);
            }
            update();
        });
        binding.allGenres.setOnCheckedChangeListener((button, checked) -> {
            if (!updating) {
                all = checked;
                update();
            }
        });
        binding.search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence text, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence text, int start, int before, int count) {
                update();
            }

            @Override
            public void afterTextChanged(Editable text) {
            }
        });
        dialog.show();
        binding.cancel.setOnClickListener(view -> dialog.dismiss());
        binding.apply.setOnClickListener(view -> {
            if (vm.applyGenreFilter(state, all ? null : selected)) {
                dialog.dismiss();
            }
        });
        binding.refresh.setOnClickListener(view ->
                new MaterialAlertDialogBuilder(activity,
                        R.style.ThemeOverlay_HavalH9Music_EqDialog)
                        .setTitle(R.string.genres_refresh)
                        .setMessage(R.string.genres_refresh_confirm)
                        .setNegativeButton(R.string.action_cancel, null)
                        .setPositiveButton(R.string.genres_refresh, (confirmation, which) -> {
                            vm.refreshGenreLibrary(state);
                            dialog.dismiss();
                        }).show());
        Observer<PlayerViewModel.GenreState> observer = next -> {
            if (next == null || (state != null && (!state.volumeId.equals(next.volumeId)
                    || state.generation != next.generation))) {
                dialog.dismiss();
                return;
            }
            if (state == null || state.ready != next.ready || state.catalog != next.catalog) {
                all = next.selected == null;
                selected = all ? new HashSet<>() : new HashSet<>(next.selected);
            }
            state = next;
            update();
        };
        Observer<Boolean> busyObserver = busy -> {
            if (Boolean.TRUE.equals(busy)) {
                dialog.dismiss();
            }
        };
        dialog.setOnDismissListener(ignored -> {
            vm.getGenreState().removeObserver(observer);
            vm.getScanning().removeObserver(busyObserver);
            vm.getDataBusy().removeObserver(busyObserver);
        });
        vm.getGenreState().observe(activity, observer);
        if (dialog.isShowing()) {
            vm.getScanning().observe(activity, busyObserver);
            vm.getDataBusy().observe(activity, busyObserver);
        }
    }

    private void update() {
        if (state == null) {
            return;
        }
        updating = true;
        binding.allGenres.setChecked(all);
        binding.allGenres.setEnabled(state.ready);
        binding.search.setEnabled(state.ready);
        binding.genres.setVisibility(state.ready ? View.VISIBLE : View.GONE);
        binding.status.setVisibility(state.ready ? View.GONE : View.VISIBLE);
        binding.status.setText(state.needsRefresh ? R.string.genres_refresh_needed
                : R.string.genres_loading);
        int count = state.catalog.count(all ? null : selected);
        binding.summary.setVisibility(state.ready ? View.VISIBLE : View.GONE);
        binding.summary.setText(count == 0 ? activity.getString(R.string.genres_no_matches)
                : activity.getString(R.string.genres_count, count, state.catalog.total));
        binding.apply.setEnabled(state.ready && count > 0);
        binding.refresh.setVisibility(
                state.needsRefresh ? View.VISIBLE : View.GONE);
        adapter.visible.clear();
        String query = binding.search.getText().toString().trim().toLowerCase(Locale.ROOT);
        for (GenreFilter.Option option : state.catalog.options) {
            String name = option.key.isEmpty() ? activity.getString(R.string.genres_untagged)
                    : option.name;
            if (name.toLowerCase(Locale.ROOT).contains(query)) {
                adapter.visible.add(option);
            }
        }
        adapter.notifyDataSetChanged();
        updating = false;
    }

    private final class GenreAdapter extends BaseAdapter {
        private final List<GenreFilter.Option> visible = new ArrayList<>();

        @Override
        public int getCount() {
            return visible.size();
        }

        @Override
        public GenreFilter.Option getItem(int position) {
            return visible.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View recycled, ViewGroup parent) {
            ItemGenreBinding row = recycled == null
                    ? ItemGenreBinding.inflate(activity.getLayoutInflater(), parent, false)
                    : ItemGenreBinding.bind(recycled);
            GenreFilter.Option option = getItem(position);
            boolean checked = all || selected.contains(option.key);
            row.checked.setChecked(checked);
            row.name.setText(option.key.isEmpty()
                    ? activity.getString(R.string.genres_untagged) : option.name);
            row.hint.setVisibility(option.key.isEmpty() ? View.VISIBLE : View.GONE);
            row.count.setText(String.format(Locale.getDefault(), "%d", option.count));
            row.getRoot().setBackgroundColor(AppearanceSettings.color(activity,
                    checked ? R.attr.paletteActiveRow : R.attr.palettePanel));
            return row.getRoot();
        }
    }
}
