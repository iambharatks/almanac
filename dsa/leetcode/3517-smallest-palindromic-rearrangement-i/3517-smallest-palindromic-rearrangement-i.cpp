class Solution {
public:
    string smallestPalindrome(string s) {
        vector<int> count(26);
        for(const char &i : s){
            count[i-'a']++;
        }
        s = "";
        char extra = '/';
        for(int i = 0 ; i < 26 ; i++){
            while(count[i] > 1){
                count[i] -= 2;
                s.push_back(i+'a');
            }
            if(count[i]){
                extra = i+'a';
            }
        }
        string rev = s;
        reverse(rev.begin(),rev.end());
        if(extra != '/')
            s += extra;
        s += rev;
        return s;
    }
};