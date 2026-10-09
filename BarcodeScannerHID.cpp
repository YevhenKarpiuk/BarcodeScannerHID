#include "BarcodeScannerHID.h"
#include "ConversionWchar.h"
#include "../../include/AddInDefBase.h"

#if defined(__ANDROID__)

#include "../include/IAndroidComponentHelper.h"
#include "Android_Proj/jni/jnienv.h"
#include <jni.h>
#include <time.h>

std::wstring jstring2wstring_local(JNIEnv* jenv, jstring aStr)
{
    std::wstring result;
    if (aStr)
    {
        const jchar* pCh = jenv->GetStringChars(aStr, 0);
        jsize len = jenv->GetStringLength(aStr);
        result.assign(pCh, pCh + len);
        jenv->ReleaseStringChars(aStr, pCh);
    }
    return result;
}

class CBarcodeScannerHIDRecorder
{
private:
    IAndroidComponentHelper* helper;
    jclass                   cc;
    jobject                  obj;
    IAddInDefBaseEx*         cnn;
    ClipboardData*           shrdData;
    bool                     isChangedData;
    jmethodID                methodID_start;
    jmethodID                methodID_stop;
    jmethodID                methodID_getBarcode;
    jmethodID                methodID_clearObjectsReference;
    jmethodID                methodID_getErrorDescr;

public:
    CBarcodeScannerHIDRecorder(IAddInDefBaseEx* m_iConnect, ClipboardData* sharedData) :
        helper(0), cc(0), obj(0), cnn(m_iConnect), shrdData(sharedData), isChangedData(false),
        methodID_start(0), methodID_stop(0), methodID_getBarcode(0), methodID_clearObjectsReference(0), methodID_getErrorDescr(0)
    {
        shrdData->isComplete = false;
        shrdData->text = L"";
        shrdData->lastChange = time(NULL);

        cnn->SetEventBufferDepth(10);
        helper = (IAndroidComponentHelper*)cnn->GetInterface(eIAndroidComponentHelper);
        if (helper)
        {
            WCHAR_T* className = 0;
            convToShortWchar(&className, L"com/barcodescannerhid/lib/BarcodeScannerHIDClass");
            jclass ccloc = helper->FindClass(className);
            delete[] className;

            if (ccloc)
            {
                JNIEnv* jenv = getJniEnv();
                cc = static_cast<jclass>(jenv->NewGlobalRef(ccloc));
                jenv->DeleteLocalRef(ccloc);
                jobject activity = helper->GetActivity();
                jmethodID ctorID = jenv->GetMethodID(cc, "<init>", "(Landroid/app/Activity;J)V");
                jobject objloc = jenv->NewObject(cc, ctorID, activity, (jlong)this);
                if (objloc)
                {
                    obj = jenv->NewGlobalRef(objloc);
                    jenv->DeleteLocalRef(objloc);
                }
                jenv->DeleteLocalRef(activity);

                methodID_start = jenv->GetMethodID(cc, "startWatch", "()V");
                methodID_stop = jenv->GetMethodID(cc, "stopWatch", "()V");
                methodID_getBarcode = jenv->GetMethodID(cc, "getBarcodeText", "()Ljava/lang/String;");
                methodID_clearObjectsReference = jenv->GetMethodID(cc, "clearObjectsReference", "()V");
                methodID_getErrorDescr = jenv->GetMethodID(cc, "getErrorDescr", "()Ljava/lang/String;");
            }
        }
    }

    ~CBarcodeScannerHIDRecorder()
    {
        JNIEnv* jenv = getJniEnv();
        if (obj && methodID_clearObjectsReference)
            jenv->CallVoidMethod(obj, methodID_clearObjectsReference);
        stopWatching();
        if (obj)
            jenv->DeleteGlobalRef(obj);
        if (cc)
            jenv->DeleteGlobalRef(cc);
    }

    void updateData(const std::wstring& text)
    {
        shrdData->text = text;
        shrdData->isComplete = true;
        shrdData->lastChange = time(NULL);
        cnn->ExternalEvent(WcharWrapper(L"BarcodeScannerHID"), WcharWrapper(L"OnChange"), WcharWrapper(text.c_str()));
    }

    void startWatching()
    {
        JNIEnv* jenv = getJniEnv();
        shrdData->isComplete = false;
        if (obj && methodID_start)
            jenv->CallVoidMethod(obj, methodID_start);
    }

    void stopWatching()
    {
        JNIEnv* jenv = getJniEnv();
        if (obj && methodID_stop)
            jenv->CallVoidMethod(obj, methodID_stop);
    }

    std::wstring getBarcodeText()
    {
        JNIEnv* jenv = getJniEnv();
        jstring val = (jstring)(jenv->CallObjectMethod(obj, methodID_getBarcode));
        return jstring2wstring_local(jenv, val);
    }

    std::wstring getLastErrorDescr()
    {
        JNIEnv* jenv = getJniEnv();
        jstring err = (jstring)(jenv->CallObjectMethod(obj, methodID_getErrorDescr));
        return jstring2wstring_local(jenv, err);
    }
};

extern "C" JNIEXPORT void JNICALL Java_com_barcodescannerhid_lib_BarcodeScannerHIDClass_OnBarcodeScannerData(JNIEnv* jenv, jclass, jlong pObject, jstring content)
{
    CBarcodeScannerHIDRecorder* obj = (CBarcodeScannerHIDRecorder*)pObject;
    if (obj)
        obj->updateData(jstring2wstring_local(jenv, content));
}

#else

// Stubs for other platforms (not implemented)
class CBarcodeScannerHIDRecorder
{
public:
    CBarcodeScannerHIDRecorder(IAddInDefBaseEx*, ClipboardData*) {}
    ~CBarcodeScannerHIDRecorder() {}
    void startWatching() {}
    void stopWatching() {}
    std::wstring getBarcodeText() { return L""; }
    std::wstring getLastErrorDescr() { return L""; }
};

#endif // __ANDROID__

CBarcodeScannerHID::CBarcodeScannerHID() : piCnt(0), isRun(false), recorder(0)
{
}

CBarcodeScannerHID::~CBarcodeScannerHID()
{
    if (recorder)
    {
        delete recorder;
        recorder = 0;
    }
}

void CBarcodeScannerHID::setIConnect(IAddInDefBaseEx* piConnect)
{
    piCnt = piConnect;
    if (piCnt)
        recorder = new CBarcodeScannerHIDRecorder(piCnt, &sharedData);
}

void CBarcodeScannerHID::startWatching()
{
    if (recorder)
    {
        recorder->startWatching();
        isRun = true;
    }
}

void CBarcodeScannerHID::stopWatching()
{
    isRun = false;
    if (recorder)
        recorder->stopWatching();
}

std::wstring CBarcodeScannerHID::barcodeText()
{
    if (sharedData.isComplete)
        return sharedData.text;
    if (recorder)
        return recorder->getBarcodeText();
    return L"";
}

std::wstring CBarcodeScannerHID::getLastErrorDescr()
{
    if (recorder)
        lastErrorDescr = recorder->getLastErrorDescr();
    return lastErrorDescr;
}

void CBarcodeScannerHID::updateData(const std::wstring& text)
{
    sharedData.text = text;
    sharedData.lastChange = time(NULL);
    sharedData.isComplete = true;
}

void CBarcodeScannerHID::setError(const std::wstring& err)
{
    lastErrorDescr = err;
}
