/*
 * Copyright (c) 2016-2018 Carmen Alvarez
 *
 * This file is part of Poet Assistant.
 *
 * Poet Assistant is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * Poet Assistant is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with Poet Assistant.  If not, see <http://www.gnu.org/licenses/>.
 */

package ca.rmen.android.poetassistant.main.dictionaries.rt

import android.app.Activity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.databinding.DataBindingUtil
import androidx.databinding.ViewDataBinding
import ca.rmen.android.poetassistant.R
import ca.rmen.android.poetassistant.databinding.ListItemHeadingBinding
import ca.rmen.android.poetassistant.databinding.ListItemSubheadingBinding
import ca.rmen.android.poetassistant.databinding.ListItemWordBinding
import ca.rmen.android.poetassistant.main.Tab
import ca.rmen.android.poetassistant.main.dictionaries.ResultListAdapter
import com.google.android.flexbox.FlexboxLayoutManager

open class RTListAdapter(val tab: Tab, private val activity: Activity) : ResultListAdapter<RTEntryViewModel>(ItemCallback()) {

    class ItemCallback : DiffUtilItemCallback<RTEntryViewModel>() {
        override fun areContentsTheSame(oldItem: RTEntryViewModel, newItem: RTEntryViewModel) = oldItem == newItem
    }

    private val mWordClickedListener: OnWordClickListener = activity as OnWordClickListener

    // Where a plain tap on a word chip navigates. Favorites is not a lookup mode, so from the
    // favorites list a tapped word opens in the rhymer; every other list stays in its own mode.
    private val navigationTab: Tab = if (tab == Tab.FAVORITES) Tab.RHYMER else tab

    override fun getItemViewType(position: Int): Int {
        val entry = getItem(position)
        return entry.type.ordinal
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ResultListEntryViewHolder {
        val layoutId = when (viewType) {
            RTEntryViewModel.Type.HEADING.ordinal -> R.layout.list_item_heading
            RTEntryViewModel.Type.SUBHEADING.ordinal -> R.layout.list_item_subheading
            else -> R.layout.list_item_word
        }
        val binding = DataBindingUtil.inflate<ViewDataBinding>(LayoutInflater.from(parent.context),
                layoutId,
                parent,
                false)
        return ResultListAdapter.ResultListEntryViewHolder(parent, binding)
    }

    override fun onBindViewHolder(holder: ResultListEntryViewHolder, position: Int) {
        val viewModel = getItem(position)
        when (viewModel.type) {
            RTEntryViewModel.Type.HEADING -> {
                (holder.binding as ListItemHeadingBinding).viewModel = viewModel
                setFullSpan(holder)
            }
            RTEntryViewModel.Type.SUBHEADING -> {
                (holder.binding as ListItemSubheadingBinding).viewModel = viewModel
                setFullSpan(holder)
            }
            else -> {
                val wordBinding = holder.binding as ListItemWordBinding
                wordBinding.viewModel = viewModel
                // Tap the pill: search this word in the current mode.
                wordBinding.text1.setOnClickListener {
                    mWordClickedListener.onWordClick(viewModel.text, navigationTab)
                }
                // Long-press the pill: toggle favorite. The bound `activated` state gives the
                // chip its gold border, and the view model persists the change to the DB.
                wordBinding.text1.setOnLongClickListener { view ->
                    viewModel.isFavorite.set(!viewModel.isFavorite.get())
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    true
                }
            }
        }
        holder.binding.executePendingBindings()
    }

    // Headings and subheadings occupy a whole row in the flexbox flow, so words start on a fresh line.
    private fun setFullSpan(holder: ResultListEntryViewHolder) {
        (holder.itemView.layoutParams as? FlexboxLayoutManager.LayoutParams)?.let {
            it.flexBasisPercent = 1.0f
        }
    }
}
