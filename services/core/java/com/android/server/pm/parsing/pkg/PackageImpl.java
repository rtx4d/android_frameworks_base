/*
 * Copyright (C) 2020 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.server.pm.parsing.pkg;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.PackageParser;
import android.content.pm.SharedLibraryInfo;
import android.content.pm.parsing.ComponentParseUtils.ParsedActivity;
import android.content.pm.parsing.ComponentParseUtils.ParsedProvider;
import android.content.pm.parsing.ComponentParseUtils.ParsedService;
import android.content.pm.parsing.ParsingPackage;
import android.content.pm.parsing.ParsingPackageImpl;
import android.content.res.TypedArray;
import android.os.Environment;
import android.os.Parcel;
import android.os.UserHandle;
import android.os.storage.StorageManager;
import android.text.TextUtils;

import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.CollectionUtils;
import com.android.internal.util.DataClass;
import com.android.internal.util.Parcelling;
import com.android.internal.util.Parcelling.BuiltIn.ForInternedString;
import com.android.server.pm.parsing.PackageInfoUtils;

import java.util.List;
import java.util.UUID;

/**
 * Extensions to {@link ParsingPackageImpl} including fields/state contained in the system server
 * and not exposed to the core SDK.
 *
 * Many of the fields contained here will eventually be moved inside
 * {@link com.android.server.pm.PackageSetting} or {@link android.content.pm.PackageUserState}.
 *
 * @hide
 */
public final class PackageImpl extends ParsingPackageImpl implements ParsedPackage,
        AndroidPackageWrite, AndroidPackage {

    public static PackageImpl forParsing(@NonNull String packageName, @NonNull String baseCodePath,
            @NonNull String codePath, @NonNull TypedArray manifestArray, boolean isCoreApp) {
        return new PackageImpl(packageName, baseCodePath, codePath, manifestArray, isCoreApp);
    }

    /**
     * Mock an unavailable {@link AndroidPackage} to use when
     * removing
     * a package from the system.
     * This can occur if the package was installed on a storage device that has since been removed.
     * Since the infrastructure uses {@link AndroidPackage}, but
     * for
     * this case only cares about
     * volumeUuid, just fake it rather than having separate method paths.
     */
    public static AndroidPackage buildFakeForDeletion(String packageName, String volumeUuid) {
        return ((ParsedPackage) PackageImpl.forTesting(packageName)
                .setVolumeUuid(volumeUuid)
                .hideAsParsed())
                .hideAsFinal();
    }

    @VisibleForTesting
    public static ParsingPackage forTesting(String packageName) {
        return forTesting(packageName, "");
    }

    @VisibleForTesting
    public static ParsingPackage forTesting(String packageName, String baseCodePath) {
        return new PackageImpl(packageName, baseCodePath, baseCodePath, null, false);
    }

    @NonNull
    private long[] lastPackageUsageTimeInMills =
            new long[PackageManager.NOTIFY_PACKAGE_USE_REASONS_COUNT];

    @NonNull
    @DataClass.ParcelWith(ForInternedString.class)
    private final String manifestPackageName;

    private boolean updatedSystemApp;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String cpuAbiOverride;

    private boolean stub;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String nativeLibraryDir;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String nativeLibraryRootDir;

    private boolean nativeLibraryRootRequiresIsa;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String primaryCpuAbi;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String secondaryCpuAbi;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String secondaryNativeLibraryDir;

    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String seInfo;
    @Nullable
    @DataClass.ParcelWith(ForInternedString.class)
    protected String seInfoUser;

    private boolean coreApp;

    private boolean system;
    private boolean factoryTest;

    private boolean systemExt;
    private boolean privileged;
    private boolean oem;
    private boolean vendor;
    private boolean product;
    private boolean odm;

    private boolean signedWithPlatformKey;

    @Deprecated
    private boolean hiddenUntilInstalled;

    @Deprecated
    @Nullable
    private String credentialProtectedDataDir;
    @Deprecated
    @Nullable
    private String dataDir;
    @Deprecated
    @Nullable
    private String deviceProtectedDataDir;

    /**
     * This is an appId, the uid if the userId is == USER_SYSTEM
     */
    private int uid = -1;

    @Deprecated
    @Nullable
    private String[] usesLibraryFiles;
    @Deprecated
    @Nullable
    private List<SharedLibraryInfo> usesLibraryInfos;

    @VisibleForTesting
    public PackageImpl(@NonNull String packageName, @NonNull String baseCodePath,
            @NonNull String codePath, @Nullable TypedArray manifestArray, boolean isCoreApp) {
        super(packageName, baseCodePath, codePath, manifestArray);
        this.manifestPackageName = this.packageName;
        this.coreApp = isCoreApp;
    }

    @Override
    public ParsedPackage hideAsParsed() {
        return this;
    }

    @Override
    public AndroidPackage hideAsFinal() {
        // TODO(b/135203078): Lock as immutable
        return this;
    }

    @Override
    public int getFlags() {
        return PackageInfoUtils.appInfoFlags(this, null);
    }

    @Override
    public int getPrivateFlags() {
        return PackageInfoUtils.appInfoPrivateFlags(this, null);
    }

    @Override
    public AndroidPackageWrite mutate() {
        return this;
    }

    @Override
    public long getLongVersionCode() {
        return PackageInfo.composeLongVersionCode(versionCodeMajor, versionCode);
    }

    @Override
    public PackageImpl removePermission(int index) {
        this.permissions.remove(index);
        return this;
    }

    @Override
    public PackageImpl addUsesOptionalLibrary(int index, String libraryName) {
        this.usesOptionalLibraries = CollectionUtils.add(usesOptionalLibraries, index,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public PackageImpl addUsesLibrary(int index, String libraryName) {
        this.usesLibraries = CollectionUtils.add(usesLibraries, index,
                TextUtils.safeIntern(libraryName));
        return this;
    }

    @Override
    public PackageImpl removeUsesLibrary(String libraryName) {
        this.usesLibraries = CollectionUtils.remove(this.usesLibraries, libraryName);
        return this;
    }

    @Override
    public PackageImpl removeUsesOptionalLibrary(String libraryName) {
        super.removeUsesOptionalLibrary(libraryName);
        return this;
    }

    @Override
    public PackageImpl setSigningDetails(@Nullable PackageParser.SigningDetails value) {
        super.setSigningDetails(value);
        return this;
    }

    @Override
    public PackageImpl setRestrictUpdateHash(@Nullable byte... value) {
        super.setRestrictUpdateHash(value);
        return this;
    }

    @Override
    public PackageImpl setRealPackage(@Nullable String realPackage) {
        super.setRealPackage(realPackage);
        return this;
    }

    @Override
    public PackageImpl setPersistent(boolean value) {
        super.setPersistent(value);
        return this;
    }

    @Override
    public PackageImpl setDefaultToDeviceProtectedStorage(boolean value) {
        super.setDefaultToDeviceProtectedStorage(value);
        return this;
    }

    @Override
    public PackageImpl setDirectBootAware(boolean value) {
        super.setDirectBootAware(value);
        return this;
    }

    @Override
    public PackageImpl clearProtectedBroadcasts() {
        protectedBroadcasts.clear();
        return this;
    }

    @Override
    public PackageImpl clearOriginalPackages() {
        originalPackages.clear();
        return this;
    }

    @Override
    public PackageImpl clearAdoptPermissions() {
        adoptPermissions.clear();
        return this;
    }

    @Override
    public PackageImpl setCodePath(@NonNull String value) {
        this.codePath = value;
        return this;
    }

    // TODO(b/135203078): Move PackageManagerService#renameStaticSharedLibraryPackage
    //  into initial package parsing
    @Override
    public PackageImpl setPackageName(@NonNull String packageName) {
        this.packageName = TextUtils.safeIntern(packageName);

        int permissionsSize = permissions.size();
        for (int index = 0; index < permissionsSize; index++) {
            permissions.get(index).setPackageName(this.packageName);
        }

        int permissionGroupsSize = permissionGroups.size();
        for (int index = 0; index < permissionGroupsSize; index++) {
            permissionGroups.get(index).setPackageName(this.packageName);
        }

        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            activities.get(index).setPackageName(this.packageName);
        }

        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            receivers.get(index).setPackageName(this.packageName);
        }

        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            providers.get(index).setPackageName(this.packageName);
        }

        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            services.get(index).setPackageName(this.packageName);
        }

        int instrumentationsSize = instrumentations.size();
        for (int index = 0; index < instrumentationsSize; index++) {
            instrumentations.get(index).setPackageName(this.packageName);
        }

        return this;
    }

    @Override
    public PackageImpl setAllComponentsDirectBootAware(boolean allComponentsDirectBootAware) {
        int activitiesSize = activities.size();
        for (int index = 0; index < activitiesSize; index++) {
            activities.get(index).directBootAware = allComponentsDirectBootAware;
        }

        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            receivers.get(index).directBootAware = allComponentsDirectBootAware;
        }

        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            providers.get(index).directBootAware = allComponentsDirectBootAware;
        }

        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            services.get(index).directBootAware = allComponentsDirectBootAware;
        }

        return this;
    }

    @Override
    public PackageImpl setBaseCodePath(@NonNull String baseCodePath) {
        this.baseCodePath = TextUtils.safeIntern(baseCodePath);
        return this;
    }

    @Override
    public PackageImpl setCpuAbiOverride(@Nullable String cpuAbiOverride) {
        this.cpuAbiOverride = TextUtils.safeIntern(cpuAbiOverride);
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryDir(@Nullable String nativeLibraryDir) {
        this.nativeLibraryDir = TextUtils.safeIntern(nativeLibraryDir);
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryRootDir(@Nullable String nativeLibraryRootDir) {
        this.nativeLibraryRootDir = TextUtils.safeIntern(nativeLibraryRootDir);
        return this;
    }

    @Override
    public PackageImpl setPrimaryCpuAbi(@Nullable String primaryCpuAbi) {
        this.primaryCpuAbi = TextUtils.safeIntern(primaryCpuAbi);
        return this;
    }

    @Override
    public PackageImpl setSecondaryCpuAbi(@Nullable String secondaryCpuAbi) {
        this.secondaryCpuAbi = TextUtils.safeIntern(secondaryCpuAbi);
        return this;
    }

    @Override
    public PackageImpl setSecondaryNativeLibraryDir(@Nullable String secondaryNativeLibraryDir) {
        this.secondaryNativeLibraryDir = TextUtils.safeIntern(secondaryNativeLibraryDir);
        return this;
    }

    @Override
    public PackageImpl setSeInfo(@Nullable String seInfo) {
        this.seInfo = TextUtils.safeIntern(seInfo);
        return this;
    }

    @Override
    public PackageImpl setSeInfoUser(@Nullable String seInfoUser) {
        this.seInfoUser = TextUtils.safeIntern(seInfoUser);
        return this;
    }

    @Override
    public PackageImpl setSplitCodePaths(@Nullable String[] splitCodePaths) {
        this.splitCodePaths = splitCodePaths;
        if (splitCodePaths != null) {
            int size = splitCodePaths.length;
            for (int index = 0; index < size; index++) {
                this.splitCodePaths[index] = TextUtils.safeIntern(this.splitCodePaths[index]);
            }
        }
        return this;
    }

    @Override
    public PackageImpl capPermissionPriorities() {
        int size = permissionGroups.size();
        for (int index = size - 1; index >= 0; --index) {
            // TODO(b/135203078): Builder/immutability
            permissionGroups.get(index).priority = 0;
        }
        return this;
    }

    @Override
    public PackageImpl markNotActivitiesAsNotExportedIfSingleUser() {
        // ignore export request for single user receivers
        int receiversSize = receivers.size();
        for (int index = 0; index < receiversSize; index++) {
            ParsedActivity receiver = receivers.get(index);
            if ((receiver.flags & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                receiver.exported = false;
            }
        }

        // ignore export request for single user services
        int servicesSize = services.size();
        for (int index = 0; index < servicesSize; index++) {
            ParsedService service = services.get(index);
            if ((service.flags & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                service.exported = false;
            }
        }

        // ignore export request for single user providers
        int providersSize = providers.size();
        for (int index = 0; index < providersSize; index++) {
            ParsedProvider provider = providers.get(index);
            if ((provider.getFlags() & ActivityInfo.FLAG_SINGLE_USER) != 0) {
                provider.setExported(false);
            }
        }

        return this;
    }

    @Override
    public UUID getStorageUuid() {
        return StorageManager.convert(volumeUuid);
    }

    @Deprecated
    @Override
    public String toAppInfoToString() {
        return "ApplicationInfo{"
                + Integer.toHexString(System.identityHashCode(this))
                + " " + getPackageName() + "}";
    }

    @Override
    public PackageImpl initForUser(int userId) {
        // TODO(b/135203078): Move this user state to some other data structure
        this.uid = UserHandle.getUid(userId, UserHandle.getAppId(this.uid));

        if ("android".equals(packageName)) {
            dataDir = Environment.getDataSystemDirectory().getAbsolutePath();
            return this;
        }

        deviceProtectedDataDir = Environment
                .getDataUserDePackageDirectory(volumeUuid, userId, packageName)
                .getAbsolutePath();
        credentialProtectedDataDir = Environment
                .getDataUserCePackageDirectory(volumeUuid, userId, packageName)
                .getAbsolutePath();

        if (isDefaultToDeviceProtectedStorage()
                && PackageManager.APPLY_DEFAULT_TO_DEVICE_PROTECTED_STORAGE) {
            dataDir = deviceProtectedDataDir;
        } else {
            dataDir = credentialProtectedDataDir;
        }
        return this;
    }

    @Override
    public PackageImpl setLastPackageUsageTimeInMills(int reason, long time) {
        lastPackageUsageTimeInMills[reason] = time;
        return this;
    }

    @Override
    public long getLatestPackageUseTimeInMills() {
        long latestUse = 0L;
        for (long use : lastPackageUsageTimeInMills) {
            latestUse = Math.max(latestUse, use);
        }
        return latestUse;
    }

    @Override
    public long getLatestForegroundPackageUseTimeInMills() {
        int[] foregroundReasons = {
                PackageManager.NOTIFY_PACKAGE_USE_ACTIVITY,
                PackageManager.NOTIFY_PACKAGE_USE_FOREGROUND_SERVICE
        };

        long latestUse = 0L;
        for (int reason : foregroundReasons) {
            latestUse = Math.max(latestUse, lastPackageUsageTimeInMills[reason]);
        }
        return latestUse;
    }

    @Override
    public long[] getLastPackageUsageTimeInMills() {
        return lastPackageUsageTimeInMills;
    }

    @Override
    public PackageImpl setUsesLibraryInfos(
            @Nullable List<SharedLibraryInfo> usesLibraryInfos) {
        this.usesLibraryInfos = usesLibraryInfos;
        return this;
    }

    @Override
    public PackageImpl setUsesLibraryFiles(@Nullable String[] usesLibraryFiles) {
        this.usesLibraryFiles = usesLibraryFiles;
        return this;
    }

    @Override
    public ParsedPackage setCoreApp(boolean coreApp) {
        this.coreApp = coreApp;
        return this;
    }

    @Override
    public ParsedPackage setVersionCode(int versionCode) {
        this.versionCode = versionCode;
        return this;
    }

    @Override
    public ParsedPackage setVersionCodeMajor(int versionCodeMajor) {
        this.versionCodeMajor = versionCodeMajor;
        return this;
    }

    @Override
    public ApplicationInfo toAppInfoWithoutState() {
        ApplicationInfo appInfo = super.toAppInfoWithoutState();
        appInfo.flags = PackageInfoUtils.appInfoFlags(this, null);
        appInfo.privateFlags = PackageInfoUtils.appInfoPrivateFlags(this, null);
        appInfo.hiddenUntilInstalled = hiddenUntilInstalled;
        appInfo.nativeLibraryDir = nativeLibraryDir;
        appInfo.nativeLibraryRootDir = nativeLibraryRootDir;
        appInfo.nativeLibraryRootRequiresIsa = nativeLibraryRootRequiresIsa;
        appInfo.primaryCpuAbi = primaryCpuAbi;
        appInfo.secondaryCpuAbi = secondaryCpuAbi;
        appInfo.secondaryNativeLibraryDir = secondaryNativeLibraryDir;
        appInfo.seInfo = seInfo;
        appInfo.seInfoUser = seInfoUser;
        appInfo.uid = uid;
        appInfo.sharedLibraryFiles = usesLibraryFiles;
        appInfo.sharedLibraryInfos = usesLibraryInfos;
        appInfo.credentialProtectedDataDir = credentialProtectedDataDir;
        appInfo.dataDir = dataDir;
        appInfo.deviceProtectedDataDir = deviceProtectedDataDir;
        return appInfo;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        super.writeToParcel(dest, flags);
        dest.writeBoolean(this.updatedSystemApp);
        sForString.parcel(this.manifestPackageName, dest, flags);
        sForString.parcel(this.cpuAbiOverride, dest, flags);
        dest.writeBoolean(this.stub);
        sForString.parcel(this.nativeLibraryDir, dest, flags);
        sForString.parcel(this.nativeLibraryRootDir, dest, flags);
        dest.writeBoolean(this.nativeLibraryRootRequiresIsa);
        sForString.parcel(this.primaryCpuAbi, dest, flags);
        sForString.parcel(this.secondaryCpuAbi, dest, flags);
        sForString.parcel(this.secondaryNativeLibraryDir, dest, flags);
        sForString.parcel(this.seInfo, dest, flags);
        sForString.parcel(this.seInfoUser, dest, flags);
        dest.writeInt(this.uid);
        dest.writeBoolean(this.coreApp);
        dest.writeBoolean(this.system);
        dest.writeBoolean(this.factoryTest);
        dest.writeBoolean(this.systemExt);
        dest.writeBoolean(this.privileged);
        dest.writeBoolean(this.oem);
        dest.writeBoolean(this.vendor);
        dest.writeBoolean(this.product);
        dest.writeBoolean(this.odm);
        dest.writeBoolean(this.signedWithPlatformKey);
        dest.writeBoolean(this.hiddenUntilInstalled);
        dest.writeString(this.credentialProtectedDataDir);
        dest.writeString(this.dataDir);
        dest.writeString(this.deviceProtectedDataDir);
        dest.writeLongArray(this.lastPackageUsageTimeInMills);
        dest.writeStringArray(this.usesLibraryFiles);
        dest.writeTypedList(this.usesLibraryInfos);
    }

    public PackageImpl(Parcel in) {
        super(in);
        this.updatedSystemApp = in.readBoolean();
        this.manifestPackageName = sForString.unparcel(in);
        this.cpuAbiOverride = sForString.unparcel(in);
        this.stub = in.readBoolean();
        this.nativeLibraryDir = sForString.unparcel(in);
        this.nativeLibraryRootDir = sForString.unparcel(in);
        this.nativeLibraryRootRequiresIsa = in.readBoolean();
        this.primaryCpuAbi = sForString.unparcel(in);
        this.secondaryCpuAbi = sForString.unparcel(in);
        this.secondaryNativeLibraryDir = sForString.unparcel(in);
        this.seInfo = sForString.unparcel(in);
        this.seInfoUser = sForString.unparcel(in);
        this.uid = in.readInt();
        this.coreApp = in.readBoolean();
        this.system = in.readBoolean();
        this.factoryTest = in.readBoolean();
        this.systemExt = in.readBoolean();
        this.privileged = in.readBoolean();
        this.oem = in.readBoolean();
        this.vendor = in.readBoolean();
        this.product = in.readBoolean();
        this.odm = in.readBoolean();
        this.signedWithPlatformKey = in.readBoolean();
        this.hiddenUntilInstalled = in.readBoolean();
        this.credentialProtectedDataDir = in.readString();
        this.dataDir = in.readString();
        this.deviceProtectedDataDir = in.readString();
        this.lastPackageUsageTimeInMills = in.createLongArray();
        this.usesLibraryFiles = in.createStringArray();
        this.usesLibraryInfos = in.createTypedArrayList(SharedLibraryInfo.CREATOR);
    }

    public static final Creator<PackageImpl> CREATOR = new Creator<PackageImpl>() {
        @Override
        public PackageImpl createFromParcel(Parcel source) {
            return new PackageImpl(source);
        }

        @Override
        public PackageImpl[] newArray(int size) {
            return new PackageImpl[size];
        }
    };

    @NonNull
    @Override
    public String getManifestPackageName() {
        return manifestPackageName;
    }

    @Override
    public boolean isUpdatedSystemApp() {
        return updatedSystemApp;
    }

    @Nullable
    @Override
    public String getCpuAbiOverride() {
        return cpuAbiOverride;
    }

    @DataClass.Generated.Member
    public boolean isStub() {
        return stub;
    }

    @Nullable
    @Override
    public String getNativeLibraryDir() {
        return nativeLibraryDir;
    }

    @Nullable
    @Override
    public String getNativeLibraryRootDir() {
        return nativeLibraryRootDir;
    }

    @Override
    public boolean isNativeLibraryRootRequiresIsa() {
        return nativeLibraryRootRequiresIsa;
    }

    @Nullable
    @Override
    public String getPrimaryCpuAbi() {
        return primaryCpuAbi;
    }

    @Nullable
    @Override
    public String getSecondaryCpuAbi() {
        return secondaryCpuAbi;
    }

    @Nullable
    @Override
    public String getSecondaryNativeLibraryDir() {
        return secondaryNativeLibraryDir;
    }

    @Nullable
    @Override
    public String getSeInfo() {
        return seInfo;
    }

    @Nullable
    @Override
    public String getSeInfoUser() {
        return seInfoUser;
    }

    @Override
    public boolean isCoreApp() {
        return coreApp;
    }

    @Override
    public boolean isSystem() {
        return system;
    }

    @Override
    public boolean isFactoryTest() {
        return factoryTest;
    }

    @Override
    public boolean isSystemExt() {
        return systemExt;
    }

    @Override
    public boolean isPrivileged() {
        return privileged;
    }

    @Override
    public boolean isOem() {
        return oem;
    }

    @Override
    public boolean isVendor() {
        return vendor;
    }

    @Override
    public boolean isProduct() {
        return product;
    }

    @Override
    public boolean isOdm() {
        return odm;
    }

    @Override
    public boolean isSignedWithPlatformKey() {
        return signedWithPlatformKey;
    }

    @Deprecated
    @Override
    public boolean isHiddenUntilInstalled() {
        return hiddenUntilInstalled;
    }

    @Deprecated
    @Nullable
    @Override
    public String getCredentialProtectedDataDir() {
        return credentialProtectedDataDir;
    }

    @Deprecated
    @Nullable
    @Override
    public String getDataDir() {
        return dataDir;
    }

    @Deprecated
    @Nullable
    @Override
    public String getDeviceProtectedDataDir() {
        return deviceProtectedDataDir;
    }

    /**
     * This is an appId, the uid if the userId is == USER_SYSTEM
     */
    @Override
    public int getUid() {
        return uid;
    }

    @Deprecated
    @Nullable
    @Override
    public String[] getUsesLibraryFiles() {
        return usesLibraryFiles;
    }

    @Deprecated
    @Nullable
    @Override
    public List<SharedLibraryInfo> getUsesLibraryInfos() {
        return usesLibraryInfos;
    }

    @Override
    public PackageImpl setUpdatedSystemApp(boolean value) {
        updatedSystemApp = value;
        return this;
    }

    @DataClass.Generated.Member
    public PackageImpl setStub(boolean value) {
        stub = value;
        return this;
    }

    @Override
    public PackageImpl setNativeLibraryRootRequiresIsa(boolean value) {
        nativeLibraryRootRequiresIsa = value;
        return this;
    }

    @Override
    public PackageImpl setSystem(boolean value) {
        system = value;
        return this;
    }

    @Override
    public PackageImpl setFactoryTest(boolean value) {
        factoryTest = value;
        return this;
    }

    @Override
    public PackageImpl setSystemExt(boolean value) {
        systemExt = value;
        return this;
    }

    @Override
    public PackageImpl setPrivileged(boolean value) {
        privileged = value;
        return this;
    }

    @Override
    public PackageImpl setOem(boolean value) {
        oem = value;
        return this;
    }

    @Override
    public PackageImpl setVendor(boolean value) {
        vendor = value;
        return this;
    }

    @Override
    public PackageImpl setProduct(boolean value) {
        product = value;
        return this;
    }

    @Override
    public PackageImpl setOdm(boolean value) {
        odm = value;
        return this;
    }

    @Override
    public PackageImpl setSignedWithPlatformKey(boolean value) {
        signedWithPlatformKey = value;
        return this;
    }

    @DataClass.Generated.Member
    public PackageImpl setHiddenUntilInstalled(@Deprecated boolean value) {
        hiddenUntilInstalled = value;
        return this;
    }

    @DataClass.Generated.Member
    public PackageImpl setCredentialProtectedDataDir(@Deprecated @Nullable String value) {
        credentialProtectedDataDir = value;
        return this;
    }

    @DataClass.Generated.Member
    public PackageImpl setDataDir(@Deprecated @Nullable String value) {
        dataDir = value;
        return this;
    }

    @DataClass.Generated.Member
    public PackageImpl setDeviceProtectedDataDir(@Deprecated @Nullable String value) {
        deviceProtectedDataDir = value;
        return this;
    }

    /**
     * This is an appId, the uid if the userId is == USER_SYSTEM
     */
    @Override
    public PackageImpl setUid(int value) {
        uid = value;
        return this;
    }
}
