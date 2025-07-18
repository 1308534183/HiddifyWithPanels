#include <flutter/dart_project.h>
#include <flutter/flutter_view_controller.h>
#include <windows.h>

#include "flutter_window.h"
#include "utils.h"
#include <protocol_handler_windows/protocol_handler_windows_plugin_c_api.h>
#include <tchar.h>
#include <strsafe.h>

void RunUpdaterInBackground() {
  STARTUPINFO si;
  PROCESS_INFORMATION pi;
  ZeroMemory(&si, sizeof(si));
  si.cb = sizeof(si);
  si.dwFlags |= STARTF_USESHOWWINDOW;
  si.wShowWindow = SW_HIDE;

  ZeroMemory(&pi, sizeof(pi));

  if (CreateProcess(
          L"update.exe",   // 可执行文件名（与主程序同目录）
          NULL,            // 参数
          NULL,            // 安全属性
          NULL,            // 线程属性
          FALSE,           // 不继承句柄
          CREATE_NO_WINDOW, // 静默，无控制台窗口
          NULL,            // 环境变量
          NULL,            // 当前目录
          &si, &pi)) {
    CloseHandle(pi.hProcess);
    CloseHandle(pi.hThread);
  } else {
    MessageBox(NULL, L"启动失败", L"提示", MB_OK | MB_ICONERROR);
  }
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

  if (!::AttachConsole(ATTACH_PARENT_PROCESS) && ::IsDebuggerPresent()) {
    CreateAndAttachConsole();
  }

  ::CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);

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

  RunUpdaterInBackground();

  ::MSG msg;
  while (::GetMessage(&msg, nullptr, 0, 0)) {
    ::TranslateMessage(&msg);
    ::DispatchMessage(&msg);
  }

  ::CoUninitialize();
  ReleaseMutex(hMutexInstance);
  return EXIT_SUCCESS;
}
