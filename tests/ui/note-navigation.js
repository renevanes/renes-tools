async function noteList(page){await page.click('#note-tab-list');}
async function noteChildren(page){await page.click('#note-tab-children');}
async function noteExtra(page,group){
  await page.click('#note-tab-extra');
  if(group&&!await page.locator('#note-extra-'+group).evaluate(el=>el.open))await page.locator('#note-extra-'+group+' > summary').click();
}
module.exports={noteList,noteChildren,noteExtra};
