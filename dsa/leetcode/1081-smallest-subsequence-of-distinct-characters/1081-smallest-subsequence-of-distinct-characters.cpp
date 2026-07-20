class Solution {
public:
    string smallestSubsequence(string s) {
        //monotonic-static
        string st;
        vector<int> count(26);
        for(char &c : s){
            count[c-'a']++;
        }
        vector<bool> vis(26,false);
        for(int i = 0 ; i < size(s); i++){
            if(!vis[s[i]-'a']){
                while(!st.empty() && st.back() > s[i] ){
                    if(count[st.back()-'a'] > 0){
                        vis[st.back()-'a'] = false;
                        st.pop_back();
                    }else break;
                }
                st.push_back(s[i]);
                vis[s[i]-'a'] = true;
            }
            count[s[i]-'a']--;
        }
        return st;
    }
};