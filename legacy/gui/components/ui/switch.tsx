"use client"

import * as React from "react"
import * as SwitchPrimitive from "@radix-ui/react-switch"

import { cn } from "@/lib/utils"

function Switch({
  className,
  ...props
}: React.ComponentProps<typeof SwitchPrimitive.Root>) {
  return (
    <SwitchPrimitive.Root
      data-slot="switch"
      className={cn(
        "peer inline-flex h-5 w-10 shrink-0 items-center rounded-full border border-transparent shadow-xs transition-all outline-none cursor-pointer",
        "data-[state=checked]:bg-primary data-[state=unchecked]:bg-surface-container-highest",
        "focus-visible:border-ring focus-visible:ring-ring/50 focus-visible:ring-[3px]",
        "disabled:cursor-not-allowed disabled:opacity-50",
        className
      )}
      {...props}
    >
      <SwitchPrimitive.Thumb
        data-slot="switch-thumb"
        className={cn(
          "pointer-events-none block size-4 rounded-full ring-0 transition-transform px-[2px]",
          "data-[state=checked]:translate-x-5 data-[state=unchecked]:translate-x-0",
          "data-[state=checked]:bg-white data-[state=unchecked]:bg-white/40"
        )}
      />
    </SwitchPrimitive.Root>
  )
}

export { Switch }
