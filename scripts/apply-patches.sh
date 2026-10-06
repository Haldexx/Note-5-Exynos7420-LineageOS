#!/usr/bin/env bash
#
# Copyright (C) 2026 Haldexx (https://github.com/Haldexx)
#
# SPDX-License-Identifier: Apache-2.0
#
set -euo pipefail
repo_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
build_dir=${1:?Usage: bash apply-patches.sh /absolute/android-source-directory}
cd -- "$build_dir"
[[ -f build/envsetup.sh ]] || { echo 'No complete Android source checkout here.' >&2; exit 1; }
kernel_dir=kernel/samsung/universal7420
[[ $(git -C "$kernel_dir" rev-parse HEAD) == 0e373ce6c5eb5f3aa6aea888af9bffa97313113a ]] || {
  echo 'Kernel revision differs from the validated patch baseline.' >&2; exit 1;
}
for patch in "$repo_dir"/patches/kernel/*.patch; do
  if git -C "$kernel_dir" apply --reverse --check "$patch" 2>/dev/null; then
    echo "Already applied: $(basename "$patch")"
  else
    git -C "$kernel_dir" apply --check "$patch"
    git -C "$kernel_dir" apply "$patch"
  fi
done
series_tree() {
  local dir=$1 count=$2 index tree patch
  shift 2
  index=$(mktemp)
  GIT_INDEX_FILE=$index git -C "$dir" read-tree HEAD
  for patch in "${@:1:count}"; do
    GIT_INDEX_FILE=$index git -C "$dir" apply --cached "$patch" || { rm -f -- "$index"; return 1; }
  done
  tree=$(GIT_INDEX_FILE=$index git -C "$dir" write-tree)
  rm -f -- "$index"
  echo "$tree"
}
apply_series() {
  local dir=$1 total count path tree match patch
  shift
  total=$#
  local -a trees paths
  for ((count = 0; count <= total; count++)); do
    trees[count]=$(series_tree "$dir" "$count" "$@") || {
      echo "Patch series does not apply to the pinned $dir." >&2; return 1;
    }
  done
  mapfile -t paths < <(for tree in "${trees[@]}"; do git -C "$dir" diff --name-only HEAD "$tree"; done | sort -u)
  for ((count = total; count >= 0; count--)); do
    match=1
    for path in "${paths[@]}"; do
      if git -C "$dir" cat-file -e "${trees[count]}:$path" 2>/dev/null; then
        [[ -f $dir/$path && $(git -C "$dir" hash-object -- "$path") == \
           $(git -C "$dir" rev-parse "${trees[count]}:$path") ]] || { match=0; break; }
      elif [[ -e $dir/$path ]]; then
        match=0; break
      fi
    done
    if (( match )); then
      if (( count == total )); then echo "Already applied: $dir ($total patches)"; fi
      for patch in "${@:count+1}"; do
        git -C "$dir" apply --check "$patch"
        git -C "$dir" apply "$patch"
        echo "Applied: $dir $(basename -- "$patch")"
      done
      return 0
    fi
  done
  echo "Files in $dir differ from the pinned revision plus its patches." >&2
  return 1
}
for entry in 'system/core:system-core:0e96ff13d90df568ddd788bb02b3ddb6e3641528' \
             'packages/services/Iwlan:iwlan:42b97ec9ec3f8a0f5ad4190607ad56fd749e66b7' \
             'packages/modules/Connectivity:connectivity:c9f3e7795256bd4a3f99d02e604e24fd1552c2b3' \
             'system/netd:netd:796eaca5ab9dfdd87751c51bb71fa3f26c87d034' \
             'packages/services/Telephony:telephony:7ec78c63c51a9651cfb59c7ef04cf91036eb76c1' \
             'frameworks/base:frameworks-base:c8e9a4a21efb16c923371a740b9c1c755d17ebb2' \
             'frameworks/av:frameworks-av:b6b36c1fdb3c6ffced822a054fbf035b544f0de2' \
             'frameworks/opt/telephony:frameworks-opt-telephony:566e62daf4e93be6f934001bc76d60aba087b4d6' \
             'packages/providers/TelephonyProvider:telephony-provider:e2a2ea9e708dbfec4e862cb080a393658ce44fae' \
             'bionic:bionic:ff9fe01e7e1e81febaf32b15fa561aec480a112c' \
             'system/memory/lmkd:lmkd:5352c3c8c9e26cadaf31a80b1223019b2898a385' \
             'system/memory/libmeminfo:libmeminfo:49326ec942a00db8994f2dc69796ea28c99643e1' \
             'build/make:build-make:e5aaa62172df0f321e68133fa30f42316376bfe8' \
             'hardware/samsung:hardware-samsung:48d217b79df26e3cbdbcb2195a61586e2ee9b96d' \
             'hardware/samsung_slsi-linaro/graphics:graphics:4f4788272364009a06132f9b2b62e4a042c2ea0a' \
             'build/blueprint:build-blueprint:c39c8a4c103f1393f015a5befa7726f0c14c9bc2' \
             'build/soong:build-soong:9aa045a2aef10b8089e32e847fed26d9aa3d61be' \
             'external/e2fsprogs:e2fsprogs:8045c66384370e8539576c9cdc073674737c3ffc' \
             'external/perfetto:external-perfetto:df0f96de5f5c811168454194966553c869d56a80' \
             'device/samsung/noblelte:device-noblelte:abbe69f783b6ca56c5b9b183a6741d9927f3d175' \
             'device/samsung/universal7420-common:device-common:e77d91383a634dd299e32143a4db80919505516d' \
             'vendor/samsung:vendor-samsung:4a7f3c23e8b4407b5fa1d0b68791d98cbe582c5d'; do
  IFS=: read -r source_dir patch_dir revision <<< "$entry"
  [[ $(git -C "$source_dir" rev-parse HEAD) == "$revision" ]] || {
    echo "Unexpected source revision: $source_dir" >&2; exit 1;
  }
  apply_series "$source_dir" "$repo_dir/patches/$patch_dir"/*.patch
done
config_target="$kernel_dir/arch/arm64/configs/exynos7420-nobleltespr_defconfig"
if [[ -e "$config_target" ]] && ! cmp -s "$repo_dir/kernel/exynos7420-nobleltespr_defconfig" "$config_target"; then
  echo 'Existing kernel config differs; review before replacement.' >&2; exit 1
fi
cp -- "$repo_dir/kernel/exynos7420-nobleltespr_defconfig" "$config_target"
for product in nobleltespr nobleltedv; do
  mkdir -p -- "device/samsung/$product"
  cp -a -- "$repo_dir/device/samsung/$product/." "device/samsung/$product/"
done
echo 'noblelte patches applied.'
