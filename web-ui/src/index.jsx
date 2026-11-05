import React from "react";
import CamelIntegrationsPage from "./camelRoutes";
import CamelLibraryPage from "./camelRoutes/CamelLibraryPage";
import CamelExtensionsPage from "./camelRoutes/CamelExtensionsPage";

const CamelRoutesPage = CamelIntegrationsPage;
const CamelComponentConfigurationsPage = () => <CamelLibraryPage defaultTab="connections" />;
const CamelRegistryPage = () => <CamelLibraryPage defaultTab="objects" />;
const CamelKameletsPage = () => <CamelLibraryPage defaultTab="templates" />;

export {
  CamelIntegrationsPage,
  CamelLibraryPage,
  CamelExtensionsPage,
  CamelRoutesPage,
  CamelComponentConfigurationsPage,
  CamelRegistryPage,
  CamelKameletsPage,
};
